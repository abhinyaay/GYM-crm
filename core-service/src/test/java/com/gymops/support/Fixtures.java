package com.gymops.support;

import com.gymops.branch.GymBranch;
import com.gymops.member.Member;
import com.gymops.plan.BillingPeriod;
import com.gymops.plan.CancellationPolicy;
import com.gymops.plan.MembershipPlan;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

/** In-memory domain objects for unit tests (no database). */
public final class Fixtures {

    private Fixtures() {
    }

    public static GymBranch branch(long id) {
        GymBranch b = new GymBranch("BR" + id, "Branch " + id);
        b.setTimezone("Asia/Kolkata");
        b.setCapacity(100);
        b.setCity("Pune");
        b.setCountry("IN");
        b.setAddressLine1("Street");
        ReflectionTestUtils.setField(b, "id", id);
        return b;
    }

    public static Member member(long id, GymBranch branch) {
        Member m = new Member("M%06d".formatted(id), branch, "Test", "Member" + id, "m" + id + "@example.com",
                "+91 9000000000", Instant.parse("2026-01-01T00:00:00Z"));
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }

    /** Monthly plan: 3000, 7 grace days, freeze 7-30 days once per term, prorated cancellation (fee 300). */
    public static MembershipPlan monthlyPlan() {
        MembershipPlan p = new MembershipPlan("MONTHLY");
        p.setName("Monthly");
        p.setBillingPeriod(BillingPeriod.MONTHLY);
        p.setDurationMonths(1);
        p.setPrice(new BigDecimal("3000.00"));
        p.setCurrency("INR");
        p.setJoiningFee(new BigDecimal("500.00"));
        p.setFreezeAllowed(true);
        p.setMinFreezeDays(7);
        p.setMaxFreezeDays(30);
        p.setMaxFreezeCount(1);
        p.setCancellationPolicy(CancellationPolicy.PRORATED);
        p.setCancellationFee(new BigDecimal("300.00"));
        p.setGracePeriodDays(7);
        p.activate();
        ReflectionTestUtils.setField(p, "id", 1L);
        return p;
    }

    public static MembershipPlan plan(String code, BillingPeriod period, String price, CancellationPolicy policy,
                                      int coolingOff, int grace) {
        MembershipPlan p = new MembershipPlan(code);
        p.setName(code);
        p.setBillingPeriod(period);
        if (period == BillingPeriod.CUSTOM) {
            p.setDurationDays(15);
        } else {
            p.setDurationMonths(period.months());
        }
        p.setPrice(new BigDecimal(price));
        p.setCurrency("INR");
        p.setCancellationPolicy(policy);
        p.setCoolingOffDays(coolingOff);
        p.setGracePeriodDays(grace);
        p.activate();
        return p;
    }
}
