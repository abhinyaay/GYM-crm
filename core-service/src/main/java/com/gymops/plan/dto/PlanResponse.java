package com.gymops.plan.dto;

import com.gymops.plan.BillingPeriod;
import com.gymops.plan.CancellationPolicy;
import com.gymops.plan.MembershipPlan;
import com.gymops.plan.PlanStatus;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

public record PlanResponse(
        Long id, String code, String name, String description,
        BillingPeriod billingPeriod, Integer durationMonths, Integer durationDays,
        BigDecimal price, String currency, BigDecimal joiningFee,
        AccessRules access, FreezePolicy freezePolicy, CancellationTerms cancellation,
        int gracePeriodDays, PlanStatus status, List<String> benefits, long version,
        Integer classCreditsPerMonth) {

    public record AccessRules(boolean allBranchAccess, LocalTime accessStartTime, LocalTime accessEndTime,
                              Integer maxVisitsPerMonth) {
    }

    public record FreezePolicy(boolean allowed, int minDays, int maxDays, int maxCount) {
    }

    public record CancellationTerms(CancellationPolicy policy, int coolingOffDays, BigDecimal cancellationFee) {
    }

    public static PlanResponse from(MembershipPlan p) {
        return new PlanResponse(p.getId(), p.getCode(), p.getName(), p.getDescription(),
                p.getBillingPeriod(), p.getDurationMonths(), p.getDurationDays(),
                p.getPrice(), p.getCurrency(), p.getJoiningFee(),
                new AccessRules(p.isAllBranchAccess(), p.getAccessStartTime(), p.getAccessEndTime(), p.getMaxVisitsPerMonth()),
                new FreezePolicy(p.isFreezeAllowed(), p.getMinFreezeDays(), p.getMaxFreezeDays(), p.getMaxFreezeCount()),
                new CancellationTerms(p.getCancellationPolicy(), p.getCoolingOffDays(), p.getCancellationFee()),
                p.getGracePeriodDays(), p.getStatus(), List.copyOf(p.getBenefits()), p.getVersion(),
                p.getClassCreditsPerMonth());
    }
}
