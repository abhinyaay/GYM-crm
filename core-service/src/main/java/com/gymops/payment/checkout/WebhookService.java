package com.gymops.payment.checkout;

import com.gymops.common.util.Hashing;
import com.gymops.payment.PaymentProvider;
import com.gymops.payment.autopay.AutopayAttemptRepository;
import com.gymops.payment.autopay.AutopayService;
import com.gymops.payment.gateway.PaymentGateway.WebhookEvent;
import com.gymops.payment.gateway.PaymentGateways;
import com.gymops.payment.method.MandateStatus;
import com.gymops.payment.method.PaymentMethodService;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Provider webhooks: verify the HMAC over the raw body, de-duplicate on the event id, then route
 * the event to the checkout or autopay attempt it belongs to. Processing and the PROCESSED log row
 * commit together; a failure is logged as FAILED in a separate transaction and answered with 500 so
 * the provider redelivers, and a redelivery of a FAILED event is processed again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    public enum Outcome { PROCESSED, IGNORED, DUPLICATE }

    private final PaymentGateways gateways;
    private final WebhookEventRepository events;
    private final PaymentIntentRepository intents;
    private final AutopayAttemptRepository attempts;
    private final CheckoutService checkout;
    private final AutopayService autopay;
    private final PaymentMethodService paymentMethods;
    private final PlatformTransactionManager txManager;
    private final Clock clock;

    /**
     * @throws com.gymops.payment.gateway.PaymentGateway.InvalidSignatureException if the signature does not verify
     */
    public Outcome handle(PaymentProvider provider, String rawBody, String signature, String eventIdHeader) {
        WebhookEvent event = gateways.get(provider).parseWebhook(rawBody, signature);
        String eventId = eventIdHeader != null && !eventIdHeader.isBlank() ? eventIdHeader
                : "sha256:" + Hashing.sha256Hex(rawBody);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            return tx.execute(status -> {
                var existing = events.findByProviderAndEventId(provider.name(), eventId);
                if (existing.isPresent() && !"FAILED".equals(existing.get().getStatus())) {
                    return Outcome.DUPLICATE;
                }
                Outcome outcome = route(provider, event);
                if (existing.isPresent()) {
                    existing.get().retried(outcome.name(), null);
                } else {
                    events.saveAndFlush(new WebhookEventLog(provider.name(), eventId, event.type(), rawBody,
                            outcome.name(), null, Instant.now(clock)));
                }
                return outcome;
            });
        } catch (RuntimeException e) {
            log.error("Webhook {} {} failed: {}", provider, event.type(), e.getMessage(), e);
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                var existing = events.findByProviderAndEventId(provider.name(), eventId);
                if (existing.isPresent()) {
                    existing.get().retried("FAILED", e.getMessage());
                } else {
                    events.save(new WebhookEventLog(provider.name(), eventId, event.type(), rawBody, "FAILED",
                            e.getMessage(), Instant.now(clock)));
                }
            });
            throw e;
        }
    }

    private Outcome route(PaymentProvider provider, WebhookEvent event) {
        String type = event.type() == null ? "" : event.type();
        if (type.startsWith("payment.") && event.payment() != null && event.payment().orderId() != null) {
            var payment = event.payment();
            var intent = intents.findByProviderOrderId(payment.orderId());
            if (intent.isPresent()) {
                checkout.settle(intent.get().getId(), payment);
                return Outcome.PROCESSED;
            }
            var attempt = attempts.findByProviderOrderId(payment.orderId());
            if (attempt.isPresent()) {
                autopay.settleFromProvider(attempt.get().getId(), payment);
                return Outcome.PROCESSED;
            }
            return Outcome.IGNORED;
        }
        if (type.startsWith("token.") && event.tokenId() != null) {
            MandateStatus status = switch (type) {
                case "token.confirmed" -> MandateStatus.ACTIVE;
                case "token.paused" -> MandateStatus.PAUSED;
                case "token.cancelled", "token.rejected" -> MandateStatus.REVOKED;
                default -> null;
            };
            if (status != null) {
                paymentMethods.onMandateChanged(provider, event.tokenId(), status);
                return Outcome.PROCESSED;
            }
        }
        return Outcome.IGNORED;
    }
}
