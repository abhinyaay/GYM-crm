package com.gymops.payment.method;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.notification.NotificationService;
import com.gymops.notification.NotificationType;
import com.gymops.payment.PaymentProvider;
import com.gymops.payment.gateway.PaymentGateway.ProviderPayment;
import com.gymops.payment.gateway.PaymentGateways;
import com.gymops.security.AccessPolicy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Saved cards / UPI handles: listing, default selection, removal, and capture from a checkout. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentMethodService {

    private final SavedPaymentMethodRepository repository;
    private final MemberRepository memberRepository;
    private final PaymentGateways gateways;
    private final AccessPolicy access;
    private final AuditService audit;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<SavedPaymentMethodResponse> list(Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        return repository.findActiveByMember(memberId).stream().map(SavedPaymentMethodResponse::from).toList();
    }

    @Transactional
    public List<SavedPaymentMethodResponse> makeDefault(Long memberId, Long methodId) {
        Member member = lockMember(memberId);
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        SavedPaymentMethod target = loadActive(memberId, methodId);
        repository.findActiveByMember(memberId).stream().filter(SavedPaymentMethod::isDefaultMethod)
                .forEach(m -> m.makeDefault(false));
        repository.flush();                                  // partial unique index: one default per member
        target.makeDefault(true);
        repository.flush();
        return list(memberId);
    }

    /** Removes a saved method; autopay that relied on it is switched off (via {@link SavedMethodRevoked}). */
    @Transactional
    public void revoke(Long memberId, Long methodId) {
        Member member = lockMember(memberId);
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        SavedPaymentMethod method = loadActive(memberId, methodId);
        boolean wasDefault = method.isDefaultMethod();
        method.revoke(Instant.now(clock));
        repository.flush();
        if (wasDefault) {
            repository.findActiveByMember(memberId).stream().findFirst().ifPresent(m -> m.makeDefault(true));
        }
        events.publishEvent(new SavedMethodRevoked(method.getId(), memberId, "Payment method removed"));
        audit.record(AuditEvent.of("PAYMENT_METHOD", method.getId(), "REVOKED").member(memberId)
                .with("label", method.getLabel()));
        String customerId = method.getProviderCustomerId();
        String token = method.getProviderToken();
        gateways.find(method.getProvider()).ifPresent(gateway -> afterCommit(() -> gateway.deleteToken(customerId, token)));
    }

    /**
     * Stores the token produced by a checkout (idempotent per provider token). Caller holds the member lock.
     *
     * @param mandateMax non-null when the checkout also authorised recurring debits (autopay)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SavedPaymentMethod saveFromCheckout(Member member, PaymentProvider provider, ProviderPayment payment,
                                               BigDecimal mandateMax, LocalDate mandateExpiresAt) {
        SavedPaymentMethod method = repository.findByProviderAndProviderToken(provider, payment.tokenId()).orElse(null);
        if (method != null) {
            if (!method.getMember().getId().equals(member.getId())) {
                throw new BusinessRuleException("This payment method belongs to another member");
            }
        } else {
            method = new SavedPaymentMethod(member, provider, payment.method() == com.gymops.payment.PaymentMethod.UPI
                    ? com.gymops.payment.PaymentMethod.UPI : com.gymops.payment.PaymentMethod.CARD,
                    payment.customerId(), payment.tokenId(), payment.tokenBrand(), payment.tokenLast4(),
                    payment.tokenExpMonth(), payment.tokenExpYear(), payment.tokenVpaMasked());
            boolean first = repository.findActiveByMember(member.getId()).stream().noneMatch(SavedPaymentMethod::isDefaultMethod);
            method.makeDefault(first);
            repository.saveAndFlush(method);
            notifications.notifyMember(member, NotificationType.PAYMENT_METHOD_SAVED, "Payment method saved",
                    method.getLabel() + " was saved to your account for faster payments.", null);
            audit.record(AuditEvent.of("PAYMENT_METHOD", method.getId(), "SAVED").member(member.getId())
                    .with("label", method.getLabel()).with("provider", provider));
        }
        if (mandateMax != null && method.getMandateStatus() != MandateStatus.ACTIVE) {
            method.activateMandate(mandateMax, mandateExpiresAt);
        }
        return method;
    }

    /** Provider-side mandate change (e.g. the customer cancelled the e-mandate in their bank app). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onMandateChanged(PaymentProvider provider, String token, MandateStatus status) {
        repository.findByProviderAndProviderToken(provider, token).ifPresent(method -> {
            method.updateMandate(status);
            if (status == MandateStatus.REVOKED || status == MandateStatus.PAUSED) {
                events.publishEvent(new SavedMethodRevoked(method.getId(), method.getMember().getId(),
                        "Autopay mandate " + status.name().toLowerCase() + " at the bank"));
            }
            audit.record(AuditEvent.of("PAYMENT_METHOD", method.getId(), "MANDATE_" + status)
                    .member(method.getMember().getId()));
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SavedPaymentMethod loadForMember(Long memberId, Long methodId) {
        return loadActive(memberId, methodId);
    }

    private SavedPaymentMethod loadActive(Long memberId, Long methodId) {
        return repository.findById(methodId)
                .filter(m -> m.getMember().getId().equals(memberId) && m.isActive())
                .orElseThrow(() -> new ResourceNotFoundException("Payment method", methodId));
    }

    private Member lockMember(Long memberId) {
        return memberRepository.findByIdForUpdate(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    log.warn("Post-commit payment method cleanup failed: {}", e.getMessage());
                }
            }
        });
    }
}
