package com.gymops.dashboard;

import com.gymops.config.CacheConfig;
import com.gymops.dashboard.dto.AdminDashboardResponse;
import com.gymops.security.BranchScope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the admin KPI snapshot. Cached in Redis per branch scope for 60s: dashboards are polled
 * frequently by many staff screens and a minute of staleness is acceptable for aggregate KPIs.
 * Authorization happens before this is called (the scope is already validated).
 */
@Component
@RequiredArgsConstructor
public class AdminDashboardCalculator {

    private final DashboardQueries queries;
    private final AnalyticsClient analyticsClient;
    private final Clock clock;

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.ADMIN_DASHBOARD, key = "#scope.cacheKey()")
    public AdminDashboardResponse compute(BranchScope scope, Long branchIdForAnalytics, String bearerToken) {
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        YearMonth month = YearMonth.from(today);
        Instant monthStart = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();

        List<String> degraded = new ArrayList<>();
        AdminDashboardResponse.Churn churn = analyticsClient.churnSummary(branchIdForAnalytics, bearerToken)
                .map(c -> new AdminDashboardResponse.Churn(c.month(), c.churnRate(), c.churnedMembers(),
                        c.membersAtStart(), c.renewalRate()))
                .orElseGet(() -> {
                    degraded.add("memberChurn");
                    return null;
                });

        return new AdminDashboardResponse(
                scope.cacheKey(),
                today,
                queries.totalMembers(scope),
                queries.subscriptionsInStatus(scope, "ACTIVE"),
                queries.subscriptionsInStatus(scope, "FROZEN"),
                queries.subscriptionsInStatus(scope, "GRACE"),
                queries.expiredBetween(scope, month.atDay(1), today),
                queries.activeEndingBetween(scope, today, today.plusDays(7)),
                queries.collected(scope, monthStart, monthEnd),
                queries.refunded(scope, monthStart, monthEnd),
                queries.pendingInvoices(scope),
                queries.pendingAmount(scope),
                queries.overdueInvoices(scope, today),
                queries.checkIns(scope, dayStart, dayEnd),
                queries.registrations(scope, monthStart, monthEnd),
                queries.renewalsActivated(scope, monthStart, monthEnd),
                queries.openComplaints(scope),
                churn,
                degraded,
                Instant.now(clock));
    }
}
