package com.gymops.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gymops.payment.PaymentMethod;
import com.gymops.payment.PaymentProperties;
import com.gymops.payment.PaymentProvider;
import com.gymops.payment.gateway.PaymentGateway.PaymentState;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulatedPaymentGatewayTest {

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(new ObjectMapper(), props("secret-a"));

    static PaymentProperties props(String secret) {
        return new PaymentProperties(PaymentProvider.SIMULATED, secret, null, new BigDecimal("50000"), new BigDecimal("3000"),
                List.of(3, 6, 9, 12), Duration.ofMinutes(30),
                new PaymentProperties.Autopay(3, Duration.ofHours(24), List.of(1, 2, 3), null));
    }

    @Test
    void checkoutResultRoundTripsThroughSignatureVerification() {
        var result = gateway.completeCheckout("sim_order_1", 236000, true, PaymentMethod.CARD, null, true, false,
                "Mastercard", "5100", null, null);

        var payment = gateway.verifyCheckout("sim_order_1", result.paymentId(), result.signature());

        assertThat(result.paymentId().length()).isLessThanOrEqualTo(100);
        assertThat(payment.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(payment.orderId()).isEqualTo("sim_order_1");
        assertThat(payment.amountMinor()).isEqualTo(236000);
        assertThat(payment.instrument()).isEqualTo("Mastercard •••• 5100");
        assertThat(payment.tokenId()).startsWith("simtok_").doesNotStartWith("simtok_fail");
        assertThat(payment.tokenExpYear()).isEqualTo(2030);
    }

    @Test
    void signatureIsBoundToTheOrderAndTheSecret() {
        var result = gateway.completeCheckout("sim_order_1", 1000, true, PaymentMethod.UPI, null, false, false,
                null, null, "someone@okicici", null);

        assertThatThrownBy(() -> gateway.verifyCheckout("sim_order_2", result.paymentId(), result.signature()))
                .isInstanceOf(PaymentGateway.InvalidSignatureException.class);
        var otherInstance = new SimulatedPaymentGateway(new ObjectMapper(), props("secret-b"));
        assertThatThrownBy(() -> otherInstance.verifyCheckout("sim_order_1", result.paymentId(), result.signature()))
                .isInstanceOf(PaymentGateway.InvalidSignatureException.class);
        assertThat(gateway.verifyCheckout("sim_order_1", result.paymentId(), result.signature()).instrument())
                .isEqualTo("UPI so***@okicici");
    }

    @Test
    void failedCheckoutCarriesTheReasonAndNoToken() {
        var result = gateway.completeCheckout("o", 500, false, PaymentMethod.CARD, "insufficient_funds", true, false,
                null, null, null, null);
        var payment = gateway.fetchPayment(result.paymentId());
        assertThat(payment.state()).isEqualTo(PaymentState.FAILED);
        assertThat(payment.failureReason()).isEqualTo("insufficient_funds");
        assertThat(payment.hasToken()).isFalse();
    }

    @Test
    void failFutureDebitTokensAreDeclinedOnRecurringCharges() {
        var ok = gateway.completeCheckout("o", 500, true, PaymentMethod.UPI, null, true, true, null, null, "a@b", null);
        String token = gateway.fetchPayment(ok.paymentId()).tokenId();
        assertThat(token).startsWith("simtok_fail_");
        var charge = new PaymentGateway.TokenCharge("k", 500, "INR", "c", token, null, null, "d", true, Instant.now());
        assertThat(gateway.chargeToken(charge, "sim_order_x").state()).isEqualTo(PaymentState.FAILED);
        var good = new PaymentGateway.TokenCharge("k", 500, "INR", "c", "simtok_abc", null, null, "d", true, Instant.now());
        assertThat(gateway.chargeToken(good, "sim_order_x").state()).isEqualTo(PaymentState.CAPTURED);
    }

    @Test
    void webhookSignatureCoversTheRawBody() {
        String body = "{\"event\":\"token.cancelled\",\"payload\":{\"token\":{\"entity\":{\"id\":\"simtok_1\"}}}}";
        assertThat(gateway.parseWebhook(body, gateway.signWebhook(body)).tokenId()).isEqualTo("simtok_1");
        assertThatThrownBy(() -> gateway.parseWebhook(body + " ", gateway.signWebhook(body)))
                .isInstanceOf(PaymentGateway.InvalidSignatureException.class);
    }

    @Test
    void minorUnitConversionIsExact() {
        assertThat(PaymentGateway.toMinor(new BigDecimal("2360.00"))).isEqualTo(236000);
        assertThat(PaymentGateway.toMinor(new BigDecimal("0.01"))).isEqualTo(1);
        assertThat(PaymentGateway.fromMinor(236001)).isEqualByComparingTo("2360.01");
        assertThatThrownBy(() -> PaymentGateway.toMinor(new BigDecimal("1.005"))).isInstanceOf(ArithmeticException.class);
    }
}
