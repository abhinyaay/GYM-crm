package com.gymops.payment.checkout;

import com.gymops.payment.PaymentProvider;
import com.gymops.payment.checkout.CheckoutDtos.CheckoutRequest;
import com.gymops.payment.checkout.CheckoutDtos.CheckoutResponse;
import com.gymops.payment.checkout.CheckoutDtos.CheckoutResult;
import com.gymops.payment.checkout.CheckoutDtos.ConfirmRequest;
import com.gymops.payment.checkout.CheckoutDtos.PaymentConfig;
import com.gymops.payment.checkout.CheckoutDtos.SimulateRequest;
import com.gymops.payment.checkout.CheckoutDtos.SimulatedCheckout;
import com.gymops.payment.gateway.PaymentGateway.InvalidSignatureException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Online checkout & webhooks")
@RestController
@RequiredArgsConstructor
public class CheckoutController {

    private static final String PAYERS = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','MEMBER')";

    private final CheckoutService checkout;
    private final WebhookService webhooks;

    @Operation(summary = "Payment options: active provider, public checkout config, methods, EMI tenures")
    @GetMapping("/api/v1/payments/config")
    @PreAuthorize(PAYERS)
    public PaymentConfig config() {
        return checkout.config();
    }

    @Operation(summary = "Start hosted checkout for an invoice (idempotent with Idempotency-Key)",
            description = "Creates a provider order. Open Razorpay Checkout (or the simulator) with the returned "
                    + "orderId, then POST the result to /checkout/{id}/confirm.")
    @PostMapping("/api/v1/payments/checkout")
    @PreAuthorize(PAYERS)
    public ResponseEntity<CheckoutResponse> start(@RequestHeader("Idempotency-Key") String key,
                                                  @Valid @RequestBody CheckoutRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(checkout.start(request, key));
    }

    @GetMapping("/api/v1/payments/checkout/{id}")
    @PreAuthorize(PAYERS)
    public CheckoutResponse get(@PathVariable Long id) {
        return checkout.get(id);
    }

    @Operation(summary = "Simulator only: complete the hosted checkout page (success or failure)")
    @PostMapping("/api/v1/payments/checkout/{id}/simulate")
    @PreAuthorize(PAYERS)
    public SimulatedCheckout simulate(@PathVariable Long id, @Valid @RequestBody SimulateRequest request) {
        return checkout.simulate(id, request);
    }

    @Operation(summary = "Confirm checkout with the provider's payment id, order id and signature",
            description = "The signature is verified server-side before anything is recorded. 200 with outcome "
                    + "SUCCEEDED / FAILED / PENDING / ALREADY_PROCESSED / REFUNDED_DUPLICATE.")
    @PostMapping("/api/v1/payments/checkout/{id}/confirm")
    @PreAuthorize(PAYERS)
    public CheckoutResult confirm(@PathVariable Long id, @Valid @RequestBody ConfirmRequest request) {
        return checkout.confirm(id, request);
    }

    @Operation(summary = "Razorpay webhook (HMAC-SHA256 of the raw body in X-Razorpay-Signature)")
    @PostMapping("/api/v1/webhooks/razorpay")
    public ResponseEntity<Map<String, String>> razorpay(@RequestBody String body,
                                                        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                                        @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        return webhook(PaymentProvider.RAZORPAY, body, signature, eventId);
    }

    @Operation(summary = "Simulator webhook (same format and signature scheme as Razorpay)")
    @PostMapping("/api/v1/webhooks/simulated")
    public ResponseEntity<Map<String, String>> simulated(@RequestBody String body,
                                                         @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                                         @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        return webhook(PaymentProvider.SIMULATED, body, signature, eventId);
    }

    private ResponseEntity<Map<String, String>> webhook(PaymentProvider provider, String body, String signature,
                                                        String eventId) {
        if (signature == null || signature.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "missing signature"));
        }
        try {
            return ResponseEntity.ok(Map.of("status", webhooks.handle(provider, body, signature, eventId).name()));
        } catch (InvalidSignatureException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "invalid signature or payload"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", "provider not configured"));
        }
    }
}
