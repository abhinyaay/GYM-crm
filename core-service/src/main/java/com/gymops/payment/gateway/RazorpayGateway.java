package com.gymops.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gymops.payment.PaymentMethod;
import com.gymops.payment.PaymentProperties;
import com.gymops.payment.PaymentProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Razorpay adapter (REST API v1, Basic auth with key id / key secret).
 *
 * <ul>
 *   <li>Checkout: an order is created server-side; the browser opens Razorpay Checkout for it and
 *       returns {@code razorpay_payment_id / order_id / signature}; the signature is
 *       HMAC-SHA256({@code order_id|payment_id}, key secret).</li>
 *   <li>Saved methods / autopay: the first checkout runs with {@code customer_id} and a {@code token}
 *       mandate block, producing a recurring token. Later debits create an order carrying a
 *       {@code notification} (pre-debit notice) and then call {@code /payments/create/recurring}.</li>
 *   <li>Webhooks are verified with HMAC-SHA256(raw body, webhook secret).</li>
 * </ul>
 * Not constructed unless Razorpay credentials are configured.
 */
@Slf4j
public class RazorpayGateway implements PaymentGateway {

    static final String DEFAULT_BASE_URL = "https://api.razorpay.com/v1";

    private final PaymentProperties.Razorpay props;
    private final RestClient http;
    private final ObjectMapper mapper;

    public RazorpayGateway(PaymentProperties.Razorpay props, RestClient.Builder builder, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        String auth = Base64.getEncoder().encodeToString((props.keyId() + ":" + props.keySecret())
                .getBytes(StandardCharsets.UTF_8));
        this.http = builder
                .baseUrl(props.baseUrl() == null || props.baseUrl().isBlank() ? DEFAULT_BASE_URL : props.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + auth)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.RAZORPAY;
    }

    @Override
    public Map<String, Object> clientConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mode", "razorpay");
        config.put("keyId", props.keyId());                    // the key id is public by design
        config.put("businessName", props.businessName() == null ? "GymOps" : props.businessName());
        config.put("checkoutScript", "https://checkout.razorpay.com/v1/checkout.js");
        return config;
    }

    @Override
    public String ensureCustomer(Customer customer, String existingCustomerId) {
        if (existingCustomerId != null) {
            return existingCustomerId;
        }
        ObjectNode body = mapper.createObjectNode().put("name", customer.name()).put("fail_existing", "0");
        if (customer.email() != null) {
            body.put("email", customer.email());
        }
        if (customer.contact() != null) {
            body.put("contact", customer.contact());
        }
        return post("/customers", body).path("id").asText();
    }

    @Override
    public Order createOrder(OrderRequest request) {
        ObjectNode body = mapper.createObjectNode()
                .put("amount", request.amountMinor())
                .put("currency", request.currency())
                .put("receipt", truncate(request.receipt(), 40));
        notes(body, request.notes());
        if (request.recurring()) {
            body.put("method", RazorpayJson.razorpayMethod(request.method()));
            body.put("customer_id", request.customerId());
            ObjectNode token = body.putObject("token")
                    .put("max_amount", request.mandateMaxAmountMinor())
                    .put("frequency", "as_presented");
            if (request.mandateExpiresAt() != null) {
                token.put("expire_at", request.mandateExpiresAt().getEpochSecond());
            }
        }
        JsonNode order = post("/orders", body);
        return new Order(order.path("id").asText(), order.path("amount").asLong(), order.path("currency").asText());
    }

    @Override
    public ProviderPayment verifyCheckout(String orderId, String paymentId, String signature) {
        if (!Hmac.matches(Hmac.sha256Hex(props.keySecret(), orderId + "|" + paymentId), signature)) {
            throw new InvalidSignatureException("Checkout signature mismatch");
        }
        ProviderPayment payment = fetchPayment(paymentId);
        if (!orderId.equals(payment.orderId())) {
            throw new InvalidSignatureException("Payment does not belong to this order");
        }
        return payment;
    }

    @Override
    public ProviderPayment fetchPayment(String paymentId) {
        JsonNode node = get("/payments/" + paymentId + "?expand[]=card");
        ProviderPayment p = RazorpayJson.payment(node);
        if (!p.hasToken() || p.customerId() == null) {
            return p;
        }
        // card expiry and the mandate details live on the token
        try {
            JsonNode token = get("/customers/" + p.customerId() + "/tokens/" + p.tokenId());
            JsonNode card = token.path("card");
            Integer expMonth = card.hasNonNull("expiry_month") ? card.path("expiry_month").asInt() : null;
            Integer expYear = card.hasNonNull("expiry_year") ? card.path("expiry_year").asInt() : null;
            return new ProviderPayment(p.paymentId(), p.orderId(), p.state(), p.method(), p.amountMinor(), p.instrument(),
                    p.failureReason(), p.customerId(), p.tokenId(),
                    card.hasNonNull("network") ? card.path("network").asText() : p.tokenBrand(),
                    card.hasNonNull("last4") ? card.path("last4").asText() : p.tokenLast4(),
                    expMonth, expYear, p.tokenVpaMasked());
        } catch (GatewayCallException e) {
            log.warn("Could not fetch token details for payment {}: {}", paymentId, e.getMessage());
            return p;
        }
    }

    @Override
    public Order createRecurringOrder(TokenCharge request) {
        ObjectNode body = mapper.createObjectNode()
                .put("amount", request.amountMinor())
                .put("currency", request.currency())
                .put("payment_capture", true)
                .put("receipt", truncate(request.idempotencyKey(), 40));
        if (request.merchantInitiated()) {
            body.putObject("notification")
                    .put("token_id", request.token())
                    .put("payment_after", request.chargeAfter().getEpochSecond());
        }
        notes(body, Map.of("purpose", request.description()));
        JsonNode order = post("/orders", body);
        return new Order(order.path("id").asText(), order.path("amount").asLong(), order.path("currency").asText());
    }

    @Override
    public ChargeResult chargeToken(TokenCharge request, String orderId) {
        ObjectNode body = mapper.createObjectNode()
                .put("email", request.email())
                .put("contact", request.contact())
                .put("amount", request.amountMinor())
                .put("currency", request.currency())
                .put("order_id", orderId)
                .put("customer_id", request.customerId())
                .put("token", request.token())
                .put("recurring", "1")
                .put("description", request.description());
        try {
            String paymentId = post("/payments/create/recurring", body).path("razorpay_payment_id").asText(null);
            if (paymentId == null) {
                return new ChargeResult(PaymentState.PENDING, null, orderId, null, null);
            }
            ProviderPayment p = fetchPayment(paymentId);
            return new ChargeResult(p.state(), paymentId, orderId, p.instrument(), p.failureReason());
        } catch (GatewayCallException e) {
            if (e.clientError) {
                return new ChargeResult(PaymentState.FAILED, null, orderId, null, e.getMessage());
            }
            // timeout / 5xx: the debit may or may not have happened; the webhook settles it
            log.warn("Recurring debit for order {} has an unknown outcome: {}", orderId, e.getMessage());
            return new ChargeResult(PaymentState.PENDING, null, orderId, null, null);
        }
    }

    @Override
    public ChargeResult chargeTestToken(String idempotencyKey, long amountMinor, String currency, PaymentMethod method,
                                        String token) {
        throw new UnsupportedOperationByProvider("Razorpay payments must go through checkout");
    }

    @Override
    public RefundResult refund(String providerPaymentId, long amountMinor, String idempotencyKey) {
        ObjectNode body = mapper.createObjectNode().put("amount", amountMinor).put("speed", "normal");
        if (idempotencyKey != null) {
            body.put("receipt", truncate(idempotencyKey, 40));
        }
        try {
            JsonNode refund = post("/payments/" + providerPaymentId + "/refund", body);
            return new RefundResult(true, refund.path("id").asText(), null);
        } catch (GatewayCallException e) {
            return new RefundResult(false, null, e.getMessage());
        }
    }

    @Override
    public void deleteToken(String customerId, String token) {
        try {
            http.delete().uri("/customers/{c}/tokens/{t}", customerId, token).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Could not delete Razorpay token for customer {}: {}", customerId, e.getMessage());
        }
    }

    @Override
    public WebhookEvent parseWebhook(String rawBody, String signature) {
        if (props.webhookSecret() == null || props.webhookSecret().isBlank()
                || !Hmac.matches(Hmac.sha256Hex(props.webhookSecret(), rawBody), signature)) {
            throw new InvalidSignatureException("Webhook signature mismatch");
        }
        try {
            return RazorpayJson.event(mapper.readTree(rawBody));
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed webhook body", e);
        }
    }

    // ------------------------------------------------------------------ http

    private JsonNode post(String path, JsonNode body) {
        try {
            return http.post().uri(path).body(body.toString()).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new GatewayCallException(describe(e), e.getStatusCode().is4xxClientError());
        } catch (RestClientException e) {
            throw new GatewayCallException("Razorpay unreachable: " + e.getMessage(), false);
        }
    }

    private JsonNode get(String path) {
        try {
            return http.get().uri(path).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new GatewayCallException(describe(e), e.getStatusCode().is4xxClientError());
        } catch (RestClientException e) {
            throw new GatewayCallException("Razorpay unreachable: " + e.getMessage(), false);
        }
    }

    private String describe(RestClientResponseException e) {
        try {
            JsonNode error = mapper.readTree(e.getResponseBodyAsString()).path("error");
            String description = RazorpayJson.text(error, "description");
            return description != null ? description : "HTTP " + e.getStatusCode().value();
        } catch (IOException ignored) {
            return "HTTP " + e.getStatusCode().value();
        }
    }

    private void notes(ObjectNode body, Map<String, String> notes) {
        if (notes != null && !notes.isEmpty()) {
            ObjectNode n = body.putObject("notes");
            notes.forEach((k, v) -> n.put(k, truncate(v, 256)));
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** A failed call to Razorpay; {@code clientError} = the request was rejected (4xx) rather than unknown. */
    static final class GatewayCallException extends RuntimeException {
        private final boolean clientError;

        GatewayCallException(String message, boolean clientError) {
            super(message);
            this.clientError = clientError;
        }

        boolean isClientError() {
            return clientError;
        }
    }
}
