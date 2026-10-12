package com.gymops.subscription;

import com.gymops.common.util.Money;
import com.gymops.plan.MembershipPlan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Pure refund computation for a cancellation, by plan policy.
 *
 * <ul>
 *   <li>NON_REFUNDABLE: 0</li>
 *   <li>PRORATED: paid * unusedPaidDays / paidDays - cancellationFee (floored at 0)</li>
 *   <li>COOLING_OFF_FULL_REFUND: everything still refundable when cancelled within coolingOffDays of
 *       activation; PRORATED afterwards</li>
 * </ul>
 * The result is capped at the amount still refundable on the payment.
 */
public final class CancellationRefundCalculator {

    private CancellationRefundCalculator() {
    }

    public static BigDecimal refundFor(Subscription sub, BigDecimal paidAmount, BigDecimal refundable, LocalDate today,
                                       ZoneId zone) {
        MembershipPlan plan = sub.getPlan();
        BigDecimal refund = switch (plan.getCancellationPolicy()) {
            case NON_REFUNDABLE -> BigDecimal.ZERO;
            case PRORATED -> prorated(sub, paidAmount, today, plan);
            case COOLING_OFF_FULL_REFUND -> withinCoolingOff(sub, today, zone, plan)
                    ? refundable : prorated(sub, paidAmount, today, plan);
        };
        return Money.of(Money.min(Money.max(refund, BigDecimal.ZERO), refundable));
    }

    static BigDecimal prorated(Subscription sub, BigDecimal paidAmount, LocalDate today, MembershipPlan plan) {
        long paidDays = sub.paidDays();
        if (paidDays <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal unusedValue = paidAmount.multiply(BigDecimal.valueOf(sub.unusedPaidDays(today)))
                .divide(BigDecimal.valueOf(paidDays), 2, RoundingMode.HALF_UP);
        return unusedValue.subtract(plan.getCancellationFee());
    }

    static boolean withinCoolingOff(Subscription sub, LocalDate today, ZoneId zone, MembershipPlan plan) {
        if (sub.getActivatedAt() == null) {
            return true;
        }
        LocalDate activatedOn = sub.getActivatedAt().atZone(zone).toLocalDate();
        return !today.isAfter(activatedOn.plusDays(plan.getCoolingOffDays()));
    }

    /** Unused value of the current term, credited against an upgrade. */
    public static BigDecimal upgradeCredit(Subscription current, LocalDate today) {
        long paidDays = current.paidDays();
        if (paidDays <= 0) {
            return Money.ZERO;
        }
        return current.getPrice().multiply(BigDecimal.valueOf(current.unusedPaidDays(today)))
                .divide(BigDecimal.valueOf(paidDays), 2, RoundingMode.HALF_UP);
    }
}

