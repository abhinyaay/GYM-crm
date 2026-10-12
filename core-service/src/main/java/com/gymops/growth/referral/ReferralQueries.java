package com.gymops.growth.referral;

import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.growth.referral.ReferralDtos.MonthPoint;
import com.gymops.growth.referral.ReferralDtos.ReferralResponse;
import com.gymops.growth.referral.ReferralDtos.ReferralStats;
import com.gymops.growth.referral.ReferralDtos.RewardTotals;
import com.gymops.growth.referral.ReferralDtos.TopReferrer;
import com.gymops.security.AccessPolicy;
import com.gymops.security.BranchScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read models for the staff referral screens (plain SQL: joins across members, branches and rewards). */
@Component
@RequiredArgsConstructor
public class ReferralQueries {

    private static final String SELECT = """
            select r.*, rm.first_name || ' ' || rm.last_name as referrer_name, rm.member_code as referrer_code,
                   em.member_code as referee_code, b.name as branch_name,
                   w1.status as referrer_reward_status, w1.amount as referrer_reward_amount,
                   w2.status as referee_reward_status
            from referrals r
            join members rm on rm.id = r.referrer_member_id
            left join members em on em.id = r.referee_member_id
            join gym_branches b on b.id = r.branch_id
            left join referral_rewards w1 on w1.referral_id = r.id and w1.beneficiary = 'REFERRER'
            left join referral_rewards w2 on w2.referral_id = r.id and w2.beneficiary = 'REFEREE'
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;
    private final Clock clock;

    public record CodeInfo(String code, LocalDate validUntil, boolean used) {
    }

    @Transactional(readOnly = true)
    public ReferralResponse get(Long id) {
        ReferralResponse r = jdbc.query(SELECT + " where r.id = :id", Map.of("id", id), ReferralQueries::map)
                .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Referral", id));
        access.assertBranchAccess(r.branchId());
        return r;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReferralResponse> list(Long branchId, ReferralStatus status, Long referrerMemberId, int page,
                                               int size) {
        BranchScope scope = access.scope(branchId);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("all", scope.all()).addValue("branchIds", scope.ids())
                .addValue("status", status == null ? null : status.name())
                .addValue("referrer", referrerMemberId)
                .addValue("limit", size).addValue("offset", (long) page * size);
        String where = """
                 where (:all or r.branch_id in (:branchIds))
                   and (cast(:status as text) is null or r.status = cast(:status as text))
                   and (cast(:referrer as bigint) is null or r.referrer_member_id = cast(:referrer as bigint))
                """;
        Long total = jdbc.queryForObject("select count(*) from referrals r" + where, p, Long.class);
        List<ReferralResponse> rows = jdbc.query(SELECT + where + " order by r.created_at desc, r.id desc "
                + "limit :limit offset :offset", p, ReferralQueries::map);
        long count = total == null ? 0 : total;
        int pages = (int) ((count + size - 1) / size);
        return new PageResponse<>(rows, page, size, count, pages, page + 1 >= pages);
    }

    @Transactional(readOnly = true)
    public ReferralStats stats(Long branchId) {
        BranchScope scope = access.scope(branchId);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("all", scope.all()).addValue("branchIds", scope.ids());
        String inScope = " (:all or r.branch_id in (:branchIds)) ";

        Map<ReferralStatus, Long> byStatus = new EnumMap<>(ReferralStatus.class);
        for (ReferralStatus s : ReferralStatus.values()) {
            byStatus.put(s, 0L);
        }
        jdbc.query("select status, count(*) as n from referrals r where" + inScope + "group by status", p,
                rs -> {
                    byStatus.put(ReferralStatus.valueOf(rs.getString("status")), rs.getLong("n"));
                });
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long converted = byStatus.get(ReferralStatus.CONVERTED) + byStatus.get(ReferralStatus.REVERSED);
        long eligible = total - byStatus.get(ReferralStatus.REJECTED);
        double rate = eligible == 0 ? 0 : BigDecimal.valueOf(converted)
                .divide(BigDecimal.valueOf(eligible), 4, RoundingMode.HALF_UP).doubleValue();

        Map<String, RewardTotals> totals = new HashMap<>();
        jdbc.query("""
                select w.status, count(*) as n,
                       coalesce(sum(case when w.reward_type = 'STORE_CREDIT' then w.amount end), 0) as credit,
                       count(*) filter (where w.reward_type = 'DISCOUNT_CODE') as codes
                from referral_rewards w join referrals r on r.id = w.referral_id
                where""" + inScope + "group by w.status", p, rs -> {
                    totals.put(rs.getString("status"), new RewardTotals(rs.getLong("n"), rs.getBigDecimal("credit"),
                            rs.getLong("codes")));
                });
        RewardTotals none = new RewardTotals(0, BigDecimal.ZERO, 0);
        Long capped = jdbc.queryForObject("select count(*) from referrals r where" + inScope
                + "and r.referrer_reward_skipped = 'MONTHLY_CAP'", p, Long.class);

        List<TopReferrer> top = jdbc.query("""
                select m.id, m.first_name || ' ' || m.last_name as name, m.member_code, c.code,
                       count(*) as referrals,
                       count(*) filter (where r.status = 'CONVERTED') as conversions,
                       coalesce(sum(w.amount) filter (where w.status = 'AVAILABLE'), 0) as earned
                from referrals r
                join members m on m.id = r.referrer_member_id
                left join referral_codes c on c.member_id = m.id
                left join referral_rewards w on w.referral_id = r.id and w.beneficiary = 'REFERRER'
                where""" + inScope + """
                group by m.id, m.first_name, m.last_name, m.member_code, c.code
                order by conversions desc, referrals desc, m.id
                limit 10
                """, p, (rs, n) -> new TopReferrer(rs.getLong("id"), rs.getString("name"), rs.getString("member_code"),
                rs.getString("code"), rs.getLong("referrals"), rs.getLong("conversions"), rs.getBigDecimal("earned")));

        YearMonth current = YearMonth.now(clock);
        Map<String, long[]> months = new LinkedHashMap<>();
        for (int i = 5; i >= 0; i--) {
            months.put(current.minusMonths(i).toString(), new long[2]);
        }
        p.addValue("since", Timestamp.from(current.minusMonths(5).atDay(1).atStartOfDay(clock.getZone()).toInstant()))
                .addValue("zone", clock.getZone().getId());
        jdbc.query("""
                select to_char(r.created_at at time zone :zone, 'YYYY-MM') as month, count(*) as n
                from referrals r where""" + inScope + "and r.created_at >= :since group by 1", p, rs -> {
                    long[] m = months.get(rs.getString("month"));
                    if (m != null) {
                        m[0] = rs.getLong("n");
                    }
                });
        jdbc.query("""
                select to_char(r.converted_at at time zone :zone, 'YYYY-MM') as month, count(*) as n
                from referrals r where""" + inScope + "and r.converted_at >= :since and r.status = 'CONVERTED' group by 1",
                p, rs -> {
                    long[] m = months.get(rs.getString("month"));
                    if (m != null) {
                        m[1] = rs.getLong("n");
                    }
                });
        List<MonthPoint> monthly = new ArrayList<>();
        months.forEach((k, v) -> monthly.add(new MonthPoint(k, v[0], v[1])));

        return new ReferralStats(total, byStatus, rate, totals.getOrDefault("AVAILABLE", none),
                totals.getOrDefault("HELD", none), totals.getOrDefault("REVERSED", none), capped == null ? 0 : capped,
                top, monthly);
    }

    /** Code, expiry and usage of personal discount codes (for the member's own rewards). */
    @Transactional(readOnly = true)
    public Map<Long, CodeInfo> codeInfo(Collection<Long> discountIds) {
        Map<Long, CodeInfo> out = new HashMap<>();
        if (discountIds.isEmpty()) {
            return out;
        }
        jdbc.query("select id, code, valid_until, times_redeemed from discounts where id in (:ids)",
                Map.of("ids", discountIds), rs -> {
                    out.put(rs.getLong("id"), new CodeInfo(rs.getString("code"),
                            rs.getObject("valid_until", LocalDate.class), rs.getInt("times_redeemed") > 0));
                });
        return out;
    }

    private static ReferralResponse map(ResultSet rs, int n) throws SQLException {
        String last = rs.getString("referee_last_name");
        return new ReferralResponse(rs.getLong("id"), rs.getLong("referrer_member_id"), rs.getString("referrer_name"),
                rs.getString("referrer_code"), rs.getString("code"), rs.getObject("referee_member_id", Long.class),
                rs.getString("referee_code"), rs.getObject("referee_lead_id", Long.class),
                last == null ? rs.getString("referee_first_name") : rs.getString("referee_first_name") + " " + last,
                rs.getLong("branch_id"), rs.getString("branch_name"), ReferralChannel.valueOf(rs.getString("channel")),
                ReferralStatus.valueOf(rs.getString("status")), enumOrNull(RejectionReason.class, rs.getString("rejection_reason")),
                enumOrNull(RewardSkipReason.class, rs.getString("referrer_reward_skipped")), instant(rs, "created_at"),
                instant(rs, "converted_at"), instant(rs, "hold_until"), instant(rs, "reversed_at"),
                rs.getString("reversal_reason"), enumOrNull(RewardStatus.class, rs.getString("referrer_reward_status")),
                rs.getBigDecimal("referrer_reward_amount"), enumOrNull(RewardStatus.class, rs.getString("referee_reward_status")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
