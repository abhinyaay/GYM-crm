package com.gymops.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gymops.payment.PaymentMethod;
import com.gymops.payment.PaymentProperties;
import com.gymops.payment.gateway.PaymentGateway.PaymentState;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The Razorpay adapter against a mocked Razorpay API (request shapes per the public API docs). */
class RazorpayGatewayTest {

    private static final String BASE = "https://api.razorpay.test/v1";
    private static final String KEY_SECRET = "test_key_secret_value";

    private MockRestServiceServer server;
    private RazorpayGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new RazorpayGateway(new PaymentProperties.Razorpay("rzp_test_key", KEY_SECRET, "whsec_test", BASE, "GymOps"),
                builder, new ObjectMapper());
    }

    @Test
    void createsARecurringOrderWithTheMandateBlockUsingBasicAuth() {
        String basic = "Basic " + Base64.getEncoder().encodeToString(("rzp_test_key:" + KEY_SECRET).getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(BASE + "/orders")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basic))
                .andExpect(jsonPath("$.amount").value(236000))
                .andExpect(jsonPath("$.method").value("upi"))
                .andExpect(jsonPath("$.customer_id").value("cust_1"))
                .andExpect(jsonPath("$.token.max_amount").value(5000000))
                .andExpect(jsonPath("$.token.frequency").value("as_presented"))
                .andRespond(withSuccess("{\"id\":\"order_1\",\"amount\":236000,\"currency\":\"INR\"}", MediaType.APPLICATION_JSON));

        var order = gateway.createOrder(new PaymentGateway.OrderRequest(236000, "INR", "INV-1", PaymentMethod.UPI, "cust_1",
                true, 5000000, Instant.parse("2031-01-01T00:00:00Z"), Map.of("invoice", "INV-1")));

        assertThat(order.orderId()).isEqualTo("order_1");
        server.verify();
    }

    @Test
    void verifiesTheCheckoutSignatureBeforeFetchingThePayment() {
        String signature = Hmac.sha256Hex(KEY_SECRET, "order_1|pay_1");
        server.expect(requestTo(BASE + "/payments/pay_1?expand%5B%5D=card"))
                .andRespond(withSuccess("""
                        {"id":"pay_1","order_id":"order_1","status":"captured","method":"card","amount":236000,
                         "card":{"network":"Visa","last4":"4242"}}""", MediaType.APPLICATION_JSON));

        var payment = gateway.verifyCheckout("order_1", "pay_1", signature);

        assertThat(payment.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(payment.instrument()).isEqualTo("Visa •••• 4242");
        assertThatThrownBy(() -> gateway.verifyCheckout("order_1", "pay_1", "0".repeat(64)))
                .isInstanceOf(PaymentGateway.InvalidSignatureException.class);
    }

    @Test
    void recurringDebitCreatesAPaymentAgainstTheNotifiedOrder() {
        server.expect(requestTo(BASE + "/payments/create/recurring"))
                .andExpect(jsonPath("$.order_id").value("order_9"))
                .andExpect(jsonPath("$.token").value("token_1"))
                .andExpect(jsonPath("$.recurring").value("1"))
                .andRespond(withSuccess("{\"razorpay_payment_id\":\"pay_9\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/payments/pay_9?expand%5B%5D=card"))
                .andRespond(withSuccess("{\"id\":\"pay_9\",\"order_id\":\"order_9\",\"status\":\"created\",\"method\":\"upi\","
                        + "\"amount\":100}", MediaType.APPLICATION_JSON));

        var result = gateway.chargeToken(charge(), "order_9");

        assertThat(result.state()).as("bank has not confirmed yet").isEqualTo(PaymentState.PENDING);
        assertThat(result.paymentId()).isEqualTo("pay_9");
    }

    @Test
    void rejectedDebitIsAFailureButAServerErrorIsTreatedAsUnknown() {
        server.expect(requestTo(BASE + "/payments/create/recurring"))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"Token is not active\"}}"));
        var rejected = gateway.chargeToken(charge(), "order_9");
        assertThat(rejected.state()).isEqualTo(PaymentState.FAILED);
        assertThat(rejected.failureReason()).isEqualTo("Token is not active");

        server.reset();
        server.expect(requestTo(BASE + "/payments/create/recurring")).andRespond(withServerError());
        assertThat(gateway.chargeToken(charge(), "order_9").state()).isEqualTo(PaymentState.PENDING);
    }

    @Test
    void merchantInitiatedOrderCarriesThePreDebitNotification() {
        server.expect(requestTo(BASE + "/orders"))
                .andExpect(jsonPath("$.notification.token_id").value("token_1"))
                .andExpect(jsonPath("$.notification.payment_after").value(1893456000))
                .andRespond(withSuccess("{\"id\":\"order_9\",\"amount\":100,\"currency\":\"INR\"}", MediaType.APPLICATION_JSON));
        assertThat(gateway.createRecurringOrder(charge()).orderId()).isEqualTo("order_9");
        server.verify();
    }

    @Test
    void webhookRequiresTheWebhookSecretSignature() {
        String body = """
                {"event":"payment.failed","payload":{"payment":{"entity":{"id":"pay_2","order_id":"order_2",
                "status":"failed","method":"card","amount":100,"error_description":"Card expired"}}}}""";
        var event = gateway.parseWebhook(body, Hmac.sha256Hex("whsec_test", body));
        assertThat(event.type()).isEqualTo("payment.failed");
        assertThat(event.payment().failureReason()).isEqualTo("Card expired");
        assertThatThrownBy(() -> gateway.parseWebhook(body, Hmac.sha256Hex(KEY_SECRET, body)))
                .isInstanceOf(PaymentGateway.InvalidSignatureException.class);
    }

    private static PaymentGateway.TokenCharge charge() {
        return new PaymentGateway.TokenCharge("autopay:1", 100, "INR", "cust_1", "token_1", "a@b.c", "9000000000",
                "Membership renewal", true, Instant.ofEpochSecond(1893456000));
    }
}
