package com.gymops.dashboard.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Operational KPIs for admins / managers. {@code degraded} lists sections that could not be
 * computed because a dependency (the analytics service) was unavailable.
 */
public record AdminDashboardResponse(
        String scope,
        LocalDate businessDate,
        long totalMembers,
        long activeMemberships,
        long frozenMemberships,
        long membershipsInGrace,
        long expiredThisMonth,
        long expiringNext7Days,
        BigDecimal revenueThisMonth,
        BigDecimal refundsThisMonth,
        long pendingInvoices,
        BigDecimal pendingAmount,
        long overdueInvoices,
        long checkInsToday,
        long newRegistrationsThisMonth,
        long renewalsThisMonth,
        long openComplaints,
        Churn memberChurn,
        List<String> degraded,
        Instant generatedAt) {

    public record Churn(String month, Double churnRate, Integer churnedMembers, Integer membersAtStart, Double renewalRate) {
    }
}
