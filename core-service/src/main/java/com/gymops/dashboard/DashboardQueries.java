package com.gymops.dashboard;

import com.gymops.security.BranchScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only aggregate SQL for operational dashboards. Each query is a single indexed aggregate;
 * heavier historical analysis is the analytics service's job.
 */
@Repository
@RequiredArgsConstructor
public class DashboardQueries {

    private final NamedParameterJdbcTemplate jdbc;

    private static MapSqlParameterSource params(BranchScope scope) {
        return new MapSqlParameterSource().addValue("all", scope.all()).addValue("branchIds", scope.ids());
    }

    public long totalMembers(BranchScope scope) {
        return count("select count(*) from members where deleted_at is null and status = 'ACTIVE' "
                + "and (:all or home_branch_id in (:branchIds))", params(scope));
    }

    public long subscriptionsInStatus(BranchScope scope, String status) {
        return count("select count(distinct member_id) from subscriptions where status = :status "
                + "and (:all or branch_id in (:branchIds))", params(scope).addValue("status", status));
    }

    public long expiredBetween(BranchScope scope, LocalDate from, LocalDate to) {
        return count("""
                select count(*) from subscriptions s
                where s.status = 'EXPIRED' and s.end_date between :from and :to
                  and not exists (select 1 from subscriptions n where n.previous_subscription_id = s.id
                                  and n.status in ('SCHEDULED','ACTIVE','FROZEN','GRACE','EXPIRED'))
                  and (:all or s.branch_id in (:branchIds))
                """, params(scope).addValue("from", from).addValue("to", to));
    }

    public long activeEndingBetween(BranchScope scope, LocalDate from, LocalDate to) {
        return count("select count(*) from subscriptions where status = 'ACTIVE' and end_date between :from and :to "
                + "and (:all or branch_id in (:branchIds))", params(scope).addValue("from", from).addValue("to", to));
    }

    public BigDecimal collected(BranchScope scope, Instant from, Instant to) {
        return amount("""
                select coalesce(sum(p.amount), 0) from payments p join invoices i on i.id = p.invoice_id
                where p.status <> 'FAILED' and p.processed_at >= :from and p.processed_at < :to
                  and (:all or i.branch_id in (:branchIds))
                """, params(scope).addValue("from", ts(from)).addValue("to", ts(to)));
    }

    public BigDecimal refunded(BranchScope scope, Instant from, Instant to) {
        return amount("""
                select coalesce(sum(r.amount), 0) from refunds r
                join payments p on p.id = r.payment_id join invoices i on i.id = p.invoice_id
                where r.status = 'SUCCEEDED' and r.processed_at >= :from and r.processed_at < :to
                  and (:all or i.branch_id in (:branchIds))
                """, params(scope).addValue("from", ts(from)).addValue("to", ts(to)));
    }

    public long pendingInvoices(BranchScope scope) {
        return count("select count(*) from invoices where status = 'ISSUED' and (:all or branch_id in (:branchIds))",
                params(scope));
    }

    public BigDecimal pendingAmount(BranchScope scope) {
        return amount("select coalesce(sum(total_amount), 0) from invoices where status = 'ISSUED' "
                + "and (:all or branch_id in (:branchIds))", params(scope));
    }

    public long overdueInvoices(BranchScope scope, LocalDate today) {
        return count("select count(*) from invoices where status = 'ISSUED' and due_date < :today "
                + "and (:all or branch_id in (:branchIds))", params(scope).addValue("today", today));
    }

    public long checkIns(BranchScope scope, Instant from, Instant to) {
        return count("select count(*) from attendance where check_in_time >= :from and check_in_time < :to "
                + "and (:all or branch_id in (:branchIds))", params(scope).addValue("from", ts(from)).addValue("to", ts(to)));
    }

    public long registrations(BranchScope scope, Instant from, Instant to) {
        return count("select count(*) from members where deleted_at is null and registered_at >= :from "
                + "and registered_at < :to and (:all or home_branch_id in (:branchIds))",
                params(scope).addValue("from", ts(from)).addValue("to", ts(to)));
    }

    public long renewalsActivated(BranchScope scope, Instant from, Instant to) {
        return count("select count(*) from subscriptions where change_type = 'RENEWAL' and activated_at >= :from "
                + "and activated_at < :to and (:all or branch_id in (:branchIds))",
                params(scope).addValue("from", ts(from)).addValue("to", ts(to)));
    }

    public long openComplaints(BranchScope scope) {
        return count("select count(*) from complaints where status in ('OPEN','IN_PROGRESS','ESCALATED','REOPENED') "
                + "and (:all or branch_id in (:branchIds))", params(scope));
    }

    private long count(String sql, MapSqlParameterSource p) {
        Long v = jdbc.queryForObject(sql, p, Long.class);
        return v == null ? 0 : v;
    }

    private BigDecimal amount(String sql, MapSqlParameterSource p) {
        BigDecimal v = jdbc.queryForObject(sql, p, BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static java.sql.Timestamp ts(Instant i) {
        return java.sql.Timestamp.from(i);
    }
}
