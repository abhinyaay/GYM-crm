package com.gymops.notification.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.gymops.notification.NotificationProperties;
import com.gymops.notification.channel.PermanentDeliveryException;
import com.gymops.notification.channel.RecipientUnusableException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Twilio Programmable Messaging (REST {@code POST /2010-04-01/Accounts/{sid}/Messages.json}, form-encoded,
 * HTTP Basic {@code AccountSid:AuthToken}). Sends from {@code fromNumber} or through a Messaging Service.
 */
public class TwilioSmsGateway implements SmsGateway {

    /** Twilio error codes meaning "this number cannot receive this SMS": skip rather than retry. */
    private static final Set<Integer> UNREACHABLE = Set.of(
            21211,   // invalid 'To' number
            21214,   // 'To' number cannot be reached
            21408,   // permission to send to this region not enabled
            21610,   // recipient replied STOP
            21612,   // 'To' number not reachable via this 'From'
            21614);  // 'To' number is not a mobile number

    private final NotificationProperties.Twilio props;
    private final RestClient http;

    public TwilioSmsGateway(NotificationProperties.Twilio props, RestClient.Builder builder) {
        this.props = props;
        String basic = Base64.getEncoder().encodeToString(
                (nullToEmpty(props.accountSid()) + ":" + nullToEmpty(props.authToken())).getBytes(StandardCharsets.UTF_8));
        this.http = builder.baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String name() {
        return "twilio";
    }

    @Override
    public boolean configured() {
        return props.configured();
    }

    @Override
    public SmsResult send(SmsMessage m) {
        if (!configured()) {
            throw new IllegalStateException("Twilio is selected but TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN / sender are not set");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", m.to());
        form.add("Body", m.text());
        if (props.messagingServiceSid() != null && !props.messagingServiceSid().isBlank()) {
            form.add("MessagingServiceSid", props.messagingServiceSid());
        } else {
            form.add("From", props.fromNumber());
        }
        try {
            JsonNode body = http.post()
                    .uri("/2010-04-01/Accounts/{sid}/Messages.json", props.accountSid())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null || !body.hasNonNull("sid")) {
                throw new IllegalStateException("Twilio returned no message SID");
            }
            int segments = body.path("num_segments").asInt(0);
            return new SmsResult(body.get("sid").asText(), segments > 0 ? segments : SmsSegments.count(m.text()));
        } catch (RestClientResponseException e) {
            throw translate(e);
        }
    }

    private static RuntimeException translate(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        JsonNode error = parse(e);
        int code = error == null ? 0 : error.path("code").asInt(0);
        String message = "Twilio " + status + (code > 0 ? " error " + code : "")
                + (error != null && error.hasNonNull("message") ? ": " + error.get("message").asText() : "");
        if (UNREACHABLE.contains(code)) {
            return new RecipientUnusableException(message);
        }
        if (e.getStatusCode().is4xxClientError() && status != HttpStatus.TOO_MANY_REQUESTS.value()
                && status != HttpStatus.UNAUTHORIZED.value()) {
            return new PermanentDeliveryException(message, e);
        }
        return new IllegalStateException(message, e);   // 401 (credentials being rotated), 429, 5xx: retry
    }

    private static JsonNode parse(RestClientResponseException e) {
        try {
            return e.getResponseBodyAs(JsonNode.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
