package com.gymops.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymops.branch.GymBranch;
import com.gymops.member.Member;
import com.gymops.plan.BillingPeriod;
import com.gymops.plan.CancellationPolicy;
import com.gymops.plan.MembershipPlan;
import com.gymops.support.Fixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class CancellationRefundCalculatorTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate START = LocalDate.of(2026, 1, 1);    // 31-day January term
    private final GymBranch branch = Fixtures.branch(1);
    private final Member member = Fixtures.member(1, branch);

    private Subscription active(MembershipPlan plan) {
        Subscription s = Subscription.pending(member, plan, branch, START, ChangeType.NEW, null);
        s.activate(START, START.atStartOfDay(IST).toInstant());
        return s;
    }

    @Test
    void nonRefundablePlanRefundsNothing() {
        MembershipPlan plan = Fixtures.plan("NR", BillingPeriod.MONTHLY, "3100", CancellationPolicy.NON_REFUNDABLE, 0, 0);
        Subscription s = active(plan);
        assertThat(CancellationRefundCalculator.refundFor(s, new BigDecimal("3658.00"), new BigDecimal("3658.00"),
                START.plusDays(2), IST)).isEqualByComparingTo("0.00");
    }

    @Test
    void proratedRefundsUnusedDaysMinusFee() {
        MembershipPlan plan = Fixtures.plan("PR", BillingPeriod.MONTHLY, "3100", CancellationPolicy.PRORATED, 0, 0);
        plan.setCancellationFee(new BigDecimal("100.00"));
        Subscription s = active(plan);
        // cancelled on day 11 (Jan 11): 20 unused of 31 paid days -> 3100 * 20 / 31 = 2000.00, minus fee 100
        BigDecimal refund = CancellationRefundCalculator.refundFor(s, new BigDecimal("3100.00"), new BigDecimal("3100.00"),
                LocalDate.of(2026, 1, 11), IST);
        assertThat(refund).isEqualByComparingTo("1900.00");
    }

    @Test
    void coolingOffGivesFullRefundThenProrates() {
        MembershipPlan plan = Fixtures.plan("CO", BillingPeriod.MONTHLY, "3100", CancellationPolicy.COOLING_OFF_FULL_REFUND, 7, 0);
        Subscription s = active(plan);
        assertThat(CancellationRefundCalculator.refundFor(s, new BigDecimal("3658.00"), new BigDecimal("3658.00"),
                START.plusDays(7), IST)).isEqualByComparingTo("3658.00");
        assertThat(CancellationRefundCalculator.refundFor(s, new BigDecimal("3100.00"), new BigDecimal("3100.00"),
                LocalDate.of(2026, 1, 11), IST)).isEqualByComparingTo("2000.00");
    }

    @Test
    void refundIsCappedAtRefundableBalance() {
        MembershipPlan plan = Fixtures.plan("CO", BillingPeriod.MONTHLY, "3100", CancellationPolicy.COOLING_OFF_FULL_REFUND, 7, 0);
        Subscription s = active(plan);
        assertThat(CancellationRefundCalculator.refundFor(s, new BigDecimal("3100.00"), new BigDecimal("500.00"),
                START, IST)).isEqualByComparingTo("500.00");
    }

    @Test
    void upgradeCreditIsUnusedValueOfCurrentTerm() {
        MembershipPlan plan = Fixtures.plan("M", BillingPeriod.MONTHLY, "3100", CancellationPolicy.PRORATED, 0, 0);
        Subscription s = active(plan);
        assertThat(CancellationRefundCalculator.upgradeCredit(s, LocalDate.of(2026, 1, 21))).isEqualByComparingTo("1000.00");
        assertThat(CancellationRefundCalculator.upgradeCredit(s, LocalDate.of(2026, 1, 31))).isEqualByComparingTo("0.00");
    }

    @Test
    void upgradeIsDecidedByContractValue() {
        MembershipPlan monthly = Fixtures.plan("M", BillingPeriod.MONTHLY, "2499", CancellationPolicy.PRORATED, 0, 0);
        MembershipPlan annual = Fixtures.plan("A", BillingPeriod.ANNUAL, "22999", CancellationPolicy.PRORATED, 0, 0);
        Subscription onMonthly = active(monthly);
        Subscription onAnnual = active(annual);
        assertThat(SubscriptionService.isUpgrade(onMonthly, annual)).isTrue();
        assertThat(SubscriptionService.isUpgrade(onAnnual, monthly)).isFalse();
    }
}
