package com.gymops.growth.campaign;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the parameterised SQL that selects a segment's audience. Pure (no I/O) so the selection
 * rules are unit tested; all dates are computed from the business-timezone clock, never {@code now()}.
 *
 * <p>Every segment returns {@code member_id, first_name, last_name, member_code, branch_id,
 * branch_name, plan_name, end_date} for active, non-deleted members, optionally of one home branch.
 */
public final class SegmentQueryBuilder {

    /** Subscription statuses meaning "still a customer" (current, upcoming or awaiting payment). */
    static final String LIVE_STATUSES = "('ACTIVE', 'FROZEN', 'GRACE', 'SCHEDULED', 'PENDING_PAYMENT')";

    public record SegmentQuery(String sql, Map<String, Object> params) {
    }

    /** Segment parameters (a campaign's definition, saved or not). */
    public record SegmentSpec(CampaignSegment segment, ChurnBand churnBand, Integer days, Integer daysMax, Long branchId) {
    }

    private SegmentQueryBuilder() {
    }

    /**
     * @param churnMemberIds members returned by the analytics service (CHURN_RISK only, else ignored)
     */
    public static SegmentQuery build(SegmentSpec spec, LocalDate today, ZoneId zone, Collection<Long> churnMemberIds) {
        Map<String, Object> p = new HashMap<>();
        p.put("allBranches", spec.branchId() == null);
        p.put("branchId", spec.branchId() == null ? -1L : spec.branchId());
        String lateral;
        String extra;
        switch (spec.segment()) {
            case EXPIRING_NO_AUTOPAY -> {
                p.put("today", today);
                p.put("untilDate", today.plusDays(days(spec)));
                lateral = """
                        join lateral (
                            select pl.name as plan_name, s.end_date from subscriptions s
                            join membership_plans pl on pl.id = s.plan_id
                            where s.member_id = m.id and s.status = 'ACTIVE' and not s.auto_renew
                              and s.end_date between :today and :untilDate
                              and not exists (select 1 from leads l where l.trial_subscription_id = s.id)
                            order by s.end_date desc limit 1) x on true
                        """;
                extra = " and not exists (select 1 from subscriptions n where n.member_id = m.id"
                        + " and n.status in ('SCHEDULED', 'PENDING_PAYMENT'))";
            }
            case LAPSED -> {
                int min = days(spec);
                int max = Objects.requireNonNull(spec.daysMax(), "daysMax");
                p.put("endedFrom", today.minusDays(max));
                p.put("endedTo", today.minusDays(min));
                lateral = """
                        join lateral (
                            select pl.name as plan_name, s.end_date from subscriptions s
                            join membership_plans pl on pl.id = s.plan_id
                            where s.member_id = m.id and s.status = 'EXPIRED'
                              and not exists (select 1 from leads l where l.trial_subscription_id = s.id)
                            order by s.end_date desc limit 1) x on true
                        """;
                extra = " and x.end_date between :endedFrom and :endedTo"
                        + " and not exists (select 1 from subscriptions n where n.member_id = m.id and n.status in "
                        + LIVE_STATUSES + ")";
            }
            case NO_RECENT_VISIT -> {
                Instant cutoff = today.minusDays(days(spec)).atStartOfDay(zone).toInstant();
                p.put("visitCutoff", java.sql.Timestamp.from(cutoff));
                lateral = """
                        join lateral (
                            select pl.name as plan_name, s.end_date from subscriptions s
                            join membership_plans pl on pl.id = s.plan_id
                            where s.member_id = m.id and s.status = 'ACTIVE'
                              and not exists (select 1 from leads l where l.trial_subscription_id = s.id)
                            order by s.end_date desc limit 1) x on true
                        """;
                extra = " and m.registered_at < :visitCutoff"
                        + " and not exists (select 1 from attendance a where a.member_id = m.id"
                        + " and a.check_in_time >= :visitCutoff)";
            }
            case TRIAL_NOT_CONVERTED -> {
                p.put("today", today);
                p.put("trialEndedFrom", today.minusDays(days(spec)));
                lateral = """
                        join lateral (
                            select pl.name as plan_name, l.trial_ends_on as end_date from leads l
                            join subscriptions s on s.id = l.trial_subscription_id
                            join membership_plans pl on pl.id = s.plan_id
                            where l.member_id = m.id and l.status = 'TRIAL_ACTIVE'
                              and l.trial_ends_on < :today and l.trial_ends_on >= :trialEndedFrom
                            order by l.trial_ends_on desc limit 1) x on true
                        """;
                extra = " and not exists (select 1 from subscriptions n where n.member_id = m.id and n.status in "
                        + LIVE_STATUSES + ")";
            }
            case CHURN_RISK -> {
                p.put("churnIds", churnMemberIds == null || churnMemberIds.isEmpty() ? java.util.List.of(-1L)
                        : churnMemberIds);
                lateral = """
                        left join lateral (
                            select pl.name as plan_name, s.end_date from subscriptions s
                            join membership_plans pl on pl.id = s.plan_id
                            where s.member_id = m.id and s.status in ('ACTIVE', 'GRACE')
                            order by s.end_date desc limit 1) x on true
                        """;
                extra = " and m.id in (:churnIds)";
            }
            default -> throw new IllegalArgumentException("Unsupported segment " + spec.segment());
        }
        String sql = """
                select m.id as member_id, m.first_name, m.last_name, m.member_code, m.home_branch_id as branch_id,
                       b.name as branch_name, x.plan_name, x.end_date
                from members m
                join gym_branches b on b.id = m.home_branch_id
                """ + lateral + """
                where m.deleted_at is null and m.status = 'ACTIVE'
                  and (:allBranches or m.home_branch_id = :branchId)""" + extra;
        return new SegmentQuery(sql, p);
    }

    private static int days(SegmentSpec spec) {
        return Objects.requireNonNull(spec.days(), "days");
    }

    /** Short human description, e.g. "Memberships expiring in the next 7 days without autopay". */
    public static String describe(SegmentSpec spec) {
        return switch (spec.segment()) {
            case CHURN_RISK -> "Members at " + (spec.churnBand() == ChurnBand.HIGH ? "high" : "medium or high")
                    + " churn risk";
            case EXPIRING_NO_AUTOPAY -> "Memberships expiring in the next " + spec.days() + " days without autopay";
            case LAPSED -> "Members whose membership expired " + spec.days() + "–" + spec.daysMax() + " days ago";
            case NO_RECENT_VISIT -> "Active members with no visit in " + spec.days() + " days";
            case TRIAL_NOT_CONVERTED -> "Trials ended in the last " + spec.days() + " days without joining";
        };
    }
}
