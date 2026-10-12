package com.gymops.notification.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gymops.notification.NotificationProperties;
import com.gymops.notification.channel.PermanentDeliveryException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * MSG91 Flow API ({@code POST /api/v5/flow}, header {@code authkey}). Indian operators only deliver text that
 * matches a DLT-registered template, so every message names a template (per notification type, or the default)
 * and passes the title / message as {@code var1} / {@code var2}. The sender id (header) and the DLT template id
 * are sent when configured.
 */
public class Msg91SmsGateway implements SmsGateway {

    private final NotificationProperties.Msg91 props;
    private final RestClient http;
    private final ObjectMapper mapper;

    public Msg91SmsGateway(NotificationProperties.Msg91 props, RestClient.Builder builder, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = builder.baseUrl(props.baseUrl())
                .defaultHeader("authkey", props.authKey() == null ? "" : props.authKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String name() {
        return "msg91";
    }

    @Override
    public boolean configured() {
        return props.configured();
    }

    @Override
    public SmsResult send(SmsMessage m) {
        if (!configured()) {
            throw new IllegalStateException("MSG91 is selected but MSG91_AUTH_KEY / MSG91_TEMPLATE_ID are not set");
        }
        ObjectNode body = mapper.createObjectNode()
                .put("template_id", props.templateFor(m.type()))
                .put("short_url", "0");
        if (notBlank(props.senderId())) {
            body.put("sender", props.senderId());
        }
        if (notBlank(props.dltTemplateId())) {
            body.put("DLT_TE_ID", props.dltTemplateId());
        }
        body.putArray("recipients").addObject()
                .put("mobiles", m.to().startsWith("+") ? m.to().substring(1) : m.to())
                .put("var1", m.title())
                .put("var2", m.text());
        JsonNode response;
        try {
            response = http.post().uri("/api/v5/flow")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            String message = "MSG91 " + e.getStatusCode().value() + ": " + abbreviate(e.getResponseBodyAsString());
            int status = e.getStatusCode().value();
            if (e.getStatusCode().is4xxClientError() && status != HttpStatus.TOO_MANY_REQUESTS.value()
                    && status != HttpStatus.UNAUTHORIZED.value()) {
                throw new PermanentDeliveryException(message, e);
            }
            throw new IllegalStateException(message, e);
        }
        if (response == null || !"success".equalsIgnoreCase(response.path("type").asText())) {
            // MSG91 answers 200 with {"type":"error","message":"..."} for invalid template / variables / numbers
            throw new PermanentDeliveryException("MSG91 rejected the message: "
                    + (response == null ? "empty response" : abbreviate(response.path("message").asText())));
        }
        return new SmsResult(response.path("message").asText(null), SmsSegments.count(m.text()));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() <= 200 ? s : s.substring(0, 200);
    }
}
