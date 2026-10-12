package com.gymops.notification.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gymops.notification.NotificationProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Selects the SMS provider ({@code app.notifications.sms.provider}). Provider HTTP calls are bounded by timeouts. */
@Slf4j
@Configuration
public class SmsGatewayConfig {

    @Bean
    SmsGateway smsGateway(NotificationProperties props, RestClient.Builder restClientBuilder, ObjectMapper mapper) {
        NotificationProperties.Sms sms = props.sms();
        SmsGateway gateway = switch (sms.provider()) {
            case "twilio" -> new TwilioSmsGateway(sms.twilio(), withTimeouts(restClientBuilder));
            case "msg91" -> new Msg91SmsGateway(sms.msg91(), withTimeouts(restClientBuilder), mapper);
            default -> new LogSmsGateway();
        };
        if (!gateway.configured()) {
            log.warn("SMS provider '{}' is selected but its credentials are incomplete: SMS deliveries will fail until they are set",
                    gateway.name());
        }
        return gateway;
    }

    private static RestClient.Builder withTimeouts(RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return builder.clone().requestFactory(factory);
    }
}
