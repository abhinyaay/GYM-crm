package com.gymops.subscription.dto;

import com.gymops.pricing.PriceBasis;
import com.gymops.subscription.ChangeType;
import com.gymops.subscription.Subscription;
import com.gymops.subscription.SubscriptionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record SubscriptionResponse(
        Long id, Long memberId, String memberCode, Long planId, String planCode, String planName, Long branchId,
        SubscriptionStatus status, ChangeType changeType, Long previousSubscriptionId,
        LocalDate startDate, LocalDate endDate, BigDecimal price, int freezeDaysUsed, int freezeCount,
        LocalDate frozenFrom, LocalDate frozenUntil, Instant activatedAt, Instant cancelledAt,
        String cancellationReason, Long invoiceId, long version, boolean autoRenew, Long autopayMethodId,
        PriceBasis priceBasis, BigDecimal listPrice, String priceOverrideReason, boolean keepPriceOnRenewal,
        BigDecimal lockedPrice, LocalDate priceProtectedUntil) {

    public static SubscriptionResponse from(Subscription s, Long invoiceId) {
        return new SubscriptionResponse(s.getId(), s.getMember().getId(), s.getMember().getMemberCode(),
                s.getPlan().getId(), s.getPlan().getCode(), s.getPlan().getName(), s.getBranch().getId(),
                s.getStatus(), s.getChangeType(),
                s.getPreviousSubscription() == null ? null : s.getPreviousSubscription().getId(),
                s.getStartDate(), s.getEndDate(), s.getPrice(), s.getFreezeDaysUsed(), s.getFreezeCount(),
                s.getFrozenFrom(), s.getFrozenUntil(), s.getActivatedAt(), s.getCancelledAt(),
                s.getCancellationReason(), invoiceId, s.getVersion(), s.isAutoRenew(), s.getAutopayMethodId(),
                s.getPriceBasis(), s.getListPrice(), s.getPriceOverrideReason(), s.isKeepPriceOnRenewal(),
                s.getLockedPrice(), s.getPriceProtectedUntil());
    }
}
