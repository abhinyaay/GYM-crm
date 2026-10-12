package com.gymops.growth.campaign;

import com.gymops.common.web.CorrelationIdFilter;
import com.gymops.config.AppProperties;
import com.gymops.security.JwtService;
import com.gymops.security.UserPrincipal;
import com.gymops.user.RoleName;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Pages through {@code GET /analytics/members/churn-risk} on the analytics service. Same timeouts and
 * circuit breaker ({@code analytics}) as the dashboard client. Campaign runs may happen without a
 * user (scheduled), so the call carries a short-lived service token with least privilege: a MANAGER
 * of the campaign's branch, or GYM_ADMIN for an all-branch campaign. Analytics applies its own
 * branch scoping to that token.
 */
@Slf4j
@Component
public class AnalyticsChurnRiskSource implements ChurnRiskSource {

    private static final int PAGE_SIZE = 200;
    static final long SERVICE_USER_ID = 0L;

    private final RestClient restClient;
    private final JwtService jwtService;
    private final CircuitBreaker breaker;

    record Item(Long memberId, String band, Integer score) {
    }

    record Page(List<Item> items, Integer totalPages) {
    }

    public AnalyticsChurnRiskSource(RestClient.Builder builder, AppProperties props, JwtService jwtService,
                                    CircuitBreakerRegistry breakers) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.analytics().connectTimeout());
        factory.setReadTimeout(props.analytics().readTimeout());
        this.restClient = builder.baseUrl(props.analytics().baseUrl()).requestFactory(factory).build();
        this.jwtService = jwtService;
        this.breaker = breakers.circuitBreaker("analytics");
    }

    @Override
    public List<Long> membersAtRisk(ChurnBand band, Long branchId, int limit) {
        String token = "Bearer " + jwtService.issueAccessToken(servicePrincipal(branchId)).token();
        List<Long> ids = new ArrayList<>();
        try {
            for (int page = 0; ids.size() < limit; page++) {
                int current = page;
                Page body = breaker.executeSupplier(() -> fetch(band, branchId, current, token));
                if (body == null || body.items() == null || body.items().isEmpty()) {
                    break;
                }
                body.items().stream().map(Item::memberId).limit((long) limit - ids.size()).forEach(ids::add);
                if (body.totalPages() == null || current + 1 >= body.totalPages()) {
                    break;
                }
            }
        } catch (CallNotPermittedException e) {
            throw new ChurnRiskUnavailableException("analytics circuit breaker is open", e);
        } catch (RuntimeException e) {
            log.warn("Analytics churn-risk unavailable for campaign audience: {}", e.toString());
            throw new ChurnRiskUnavailableException(e.getMessage(), e);
        }
        return ids;
    }

    private Page fetch(ChurnBand band, Long branchId, int page, String token) {
        return restClient.get()
                .uri(b -> {
                    b.path("/analytics/members/churn-risk").queryParam("minBand", band.name())
                            .queryParam("page", page).queryParam("size", PAGE_SIZE);
                    if (branchId != null) {
                        b.queryParam("branchId", branchId);
                    }
                    return b.build();
                })
                .header(HttpHeaders.AUTHORIZATION, token)
                .header(CorrelationIdFilter.HEADER, MDC.get(CorrelationIdFilter.MDC_KEY))
                .retrieve()
                .body(Page.class);
    }

    static UserPrincipal servicePrincipal(Long branchId) {
        return branchId == null
                ? new UserPrincipal(SERVICE_USER_ID, "growth-campaigns@system", Set.of(RoleName.GYM_ADMIN), Set.of(), null, null)
                : new UserPrincipal(SERVICE_USER_ID, "growth-campaigns@system", Set.of(RoleName.MANAGER), Set.of(branchId),
                null, null);
    }
}
