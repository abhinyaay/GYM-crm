package com.gymops.dashboard;

import com.gymops.common.web.CorrelationIdFilter;
import com.gymops.config.AppProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for the Python analytics service. The core never depends on analytics for correctness:
 * calls have tight timeouts and a circuit breaker; on failure the caller gets {@code Optional.empty()}
 * and renders the section as degraded instead of failing the request.
 */
@Slf4j
@Component
public class AnalyticsClient {

    private final RestClient restClient;

    public record ChurnSummary(Long branchId, String month, Double churnRate, Integer churnedMembers,
                               Integer membersAtStart, Double renewalRate) {
    }

    public AnalyticsClient(RestClient.Builder builder, AppProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.analytics().connectTimeout());
        factory.setReadTimeout(props.analytics().readTimeout());
        this.restClient = builder.baseUrl(props.analytics().baseUrl()).requestFactory(factory).build();
    }

    @CircuitBreaker(name = "analytics", fallbackMethod = "churnFallback")
    public Optional<ChurnSummary> churnSummary(Long branchId, String bearerToken) {
        ChurnSummary body = restClient.get()
                .uri(b -> {
                    b.path("/analytics/memberships/churn");
                    if (branchId != null) {
                        b.queryParam("branchId", branchId);
                    }
                    return b.build();
                })
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .header(CorrelationIdFilter.HEADER, MDC.get(CorrelationIdFilter.MDC_KEY))
                .retrieve()
                .body(ChurnSummary.class);
        return Optional.ofNullable(body);
    }

    @SuppressWarnings("unused")
    private Optional<ChurnSummary> churnFallback(Long branchId, String bearerToken, Throwable error) {
        log.warn("Analytics service unavailable, dashboard churn degraded: {}", error.toString());
        return Optional.empty();
    }
}
