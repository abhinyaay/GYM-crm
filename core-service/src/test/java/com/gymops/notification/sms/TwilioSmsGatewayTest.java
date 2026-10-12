package com.gymops.notification.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gymops.notification.NotificationProperties;
import com.gymops.notification.NotificationType;
import com.gymops.notification.channel.PermanentDeliveryException;
import com.gymops.notification.channel.RecipientUnusableException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Twilio adapter against a mocked Messages API (request shape per Twilio's public REST docs). */
class TwilioSmsGatewayTest {

    private static final String BASE = "https://api.twilio.test";
    private static final String URL = BASE + "/2010-04-01/Accounts/AC123/Messages.json";
    private static final SmsGateway.SmsMessage MESSAGE = new SmsGateway.SmsMessage("+919876543210", "Payment failed",
            "Payment failed: retry at the desk", NotificationType.PAYMENT_FAILED, 9L);

    private MockRestServiceServer server;

    private TwilioSmsGateway gateway(String from, String messagingService) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new TwilioSmsGateway(new NotificationProperties.Twilio("AC123", "secret-token", from, messagingService, BASE), builder);
    }

    @Test
    void postsFormEncodedMessageWithBasicAuthAndReturnsSidAndSegments() {
        TwilioSmsGateway gateway = gateway("+15005550006", null);
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", "+919876543210");
        form.add("Body", "Payment failed: retry at the desk");
        form.add("From", "+15005550006");
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString("AC123:secret-token".getBytes(StandardCharsets.UTF_8))))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(form))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"sid\":\"SM0001\",\"status\":\"queued\",\"num_segments\":\"2\"}"));

        SmsGateway.SmsResult result = gateway.send(MESSAGE);

        assertThat(result.providerMessageId()).isEqualTo("SM0001");
        assertThat(result.segments()).isEqualTo(2);
        assertThat(gateway.configured()).isTrue();
        server.verify();
    }

    @Test
    void usesTheMessagingServiceWhenConfigured() {
        TwilioSmsGateway gateway = gateway(null, "MG777");
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", "+919876543210");
        form.add("Body", "Payment failed: retry at the desk");
        form.add("MessagingServiceSid", "MG777");
        server.expect(requestTo(URL)).andExpect(content().formData(form))
                .andRespond(withSuccess("{\"sid\":\"SM0002\"}", MediaType.APPLICATION_JSON));
        assertThat(gateway.send(MESSAGE).segments()).as("estimated locally").isEqualTo(1);
    }

    @Test
    void unreachableNumbersAreSkippedOtherClientErrorsFailAndServerErrorsRetry() {
        TwilioSmsGateway gateway = gateway("+15005550006", null);
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":21614,\"message\":\"'To' number is not a valid mobile number\",\"status\":400}"));
        assertThatThrownBy(() -> gateway.send(MESSAGE)).isInstanceOf(RecipientUnusableException.class).hasMessageContaining("21614");

        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":21602,\"message\":\"Message body is required.\",\"status\":400}"));
        assertThatThrownBy(() -> gateway.send(MESSAGE)).isInstanceOf(PermanentDeliveryException.class);

        server.reset();
        server.expect(requestTo(URL)).andRespond(withServerError());
        assertThatThrownBy(() -> gateway.send(MESSAGE)).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(PermanentDeliveryException.class);
    }

    @Test
    void missingCredentialsAreReportedWithoutCallingTwilio() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        TwilioSmsGateway gateway = new TwilioSmsGateway(new NotificationProperties.Twilio("AC123", null, "+1500", null, BASE), builder);
        assertThat(gateway.configured()).isFalse();
        assertThatThrownBy(() -> gateway.send(MESSAGE)).isInstanceOf(IllegalStateException.class).hasMessageContaining("TWILIO_");
        server.verify();
    }
}
