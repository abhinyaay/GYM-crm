package com.gymops.notification.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gymops.notification.NotificationProperties;
import com.gymops.notification.NotificationType;
import com.gymops.notification.channel.PermanentDeliveryException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** MSG91 Flow adapter against a mocked API: DLT template per type, sender id, auth key header. */
class Msg91SmsGatewayTest {

    private static final String BASE = "https://control.msg91.test";

    private MockRestServiceServer server;
    private Msg91SmsGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new Msg91SmsGateway(new NotificationProperties.Msg91("auth-key-1", "GYMOPS", "tmpl-default", "1107160000000000001",
                Map.of("AUTOPAY_SCHEDULED", "tmpl-predebit"), BASE), builder, new ObjectMapper());
    }

    @Test
    void sendsTheFlowRequestWithTheTypesTemplate() {
        server.expect(requestTo(BASE + "/api/v5/flow")).andExpect(method(HttpMethod.POST))
                .andExpect(header("authkey", "auth-key-1"))
                .andExpect(jsonPath("$.template_id").value("tmpl-predebit"))
                .andExpect(jsonPath("$.sender").value("GYMOPS"))
                .andExpect(jsonPath("$.DLT_TE_ID").value("1107160000000000001"))
                .andExpect(jsonPath("$.recipients[0].mobiles").value("919876543210"))
                .andExpect(jsonPath("$.recipients[0].var1").value("Autopay tomorrow"))
                .andExpect(jsonPath("$.recipients[0].var2").value("Rs 999 will be debited on 14 Oct"))
                .andRespond(withSuccess("{\"type\":\"success\",\"message\":\"3763646c3058373530393938\"}", MediaType.APPLICATION_JSON));

        SmsGateway.SmsResult result = gateway.send(new SmsGateway.SmsMessage("+919876543210", "Autopay tomorrow",
                "Rs 999 will be debited on 14 Oct", NotificationType.AUTOPAY_SCHEDULED, 5L));

        assertThat(result.providerMessageId()).isEqualTo("3763646c3058373530393938");
        assertThat(result.segments()).isEqualTo(1);
        server.verify();
    }

    @Test
    void fallsBackToTheDefaultTemplate() {
        server.expect(requestTo(BASE + "/api/v5/flow")).andExpect(jsonPath("$.template_id").value("tmpl-default"))
                .andRespond(withSuccess("{\"type\":\"success\",\"message\":\"req-2\"}", MediaType.APPLICATION_JSON));
        assertThat(gateway.send(new SmsGateway.SmsMessage("+919876543210", "t", "x", NotificationType.TRIAL_ENDING, 6L))
                .providerMessageId()).isEqualTo("req-2");
    }

    @Test
    void errorPayloadsAndClientErrorsArePermanentServerErrorsRetry() {
        var msg = new SmsGateway.SmsMessage("+919876543210", "t", "x", NotificationType.TRIAL_ENDING, 6L);
        server.expect(requestTo(BASE + "/api/v5/flow"))
                .andRespond(withSuccess("{\"type\":\"error\",\"message\":\"Template ID Missing\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.send(msg)).isInstanceOf(PermanentDeliveryException.class).hasMessageContaining("Template ID");

        server.reset();
        server.expect(requestTo(BASE + "/api/v5/flow")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"type\":\"error\",\"message\":\"Invalid mobile\"}"));
        assertThatThrownBy(() -> gateway.send(msg)).isInstanceOf(PermanentDeliveryException.class);

        server.reset();
        server.expect(requestTo(BASE + "/api/v5/flow")).andRespond(withServerError());
        assertThatThrownBy(() -> gateway.send(msg)).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(PermanentDeliveryException.class);
    }
}
