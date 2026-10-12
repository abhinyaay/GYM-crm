package com.gymops.payment.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gymops.payment.PaymentProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The simulator is always available (it settles legacy simulated payments and powers local demos).
 * Razorpay is registered only when its credentials are present in the environment.
 */
@Configuration
public class PaymentGatewayConfig {

    @Bean
    PaymentGateways paymentGateways(SimulatedPaymentGateway simulated, PaymentProperties props, ObjectMapper mapper,
                                    RestClient.Builder restClientBuilder) {
        List<PaymentGateway> gateways = new ArrayList<>();
        gateways.add(simulated);
        PaymentProperties.Razorpay rp = props.razorpay();
        if (rp != null && notBlank(rp.keyId()) && notBlank(rp.keySecret())) {
            var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
            factory.setReadTimeout(Duration.ofSeconds(15));
            gateways.add(new RazorpayGateway(rp, restClientBuilder.clone().requestFactory(factory), mapper));
        }
        return new PaymentGateways(gateways, props.gateway());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
