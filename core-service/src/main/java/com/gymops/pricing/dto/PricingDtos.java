package com.gymops.pricing.dto;

import com.gymops.pricing.ExistingMemberPolicy;
import com.gymops.pricing.PlanPriceChange;
import com.gymops.pricing.PriceBasis;
import com.gymops.pricing.PriceChangeSource;
import com.gymops.pricing.PriceChangeStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PricingDtos {

    private PricingDtos() {
    }

    /**
     * Schedule a price change. {@code newPrice} / {@code newJoiningFee} default to the current values;
     * PROTECT_EXISTING needs {@code protectMonths} (from the effective date) or {@code protectUntil}.
     */
    public record PriceChangeRequest(
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal newPrice,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal newJoiningFee,
            @NotNull LocalDate effectiveDate,
            @NotNull ExistingMemberPolicy existingMemberPolicy,
            @Min(1) @Max(36) Integer protectMonths,
            LocalDate protectUntil,
            @NotBlank @Size(min = 5, max = 500) String reason) {

        @AssertTrue(message = "PROTECT_EXISTING needs protectMonths or protectUntil (not both); other policies take neither")
        public boolean isProtectionValid() {
            boolean given = protectMonths != null || protectUntil != null;
            if (existingMemberPolicy != ExistingMemberPolicy.PROTECT_EXISTING) {
                return !given;
            }
            return protectMonths == null ^ protectUntil == null;
        }

        @AssertTrue(message = "newPrice or newJoiningFee is required")
        public boolean isSomethingChanging() {
            return newPrice != null || newJoiningFee != null;
        }
    }

    public record CancelRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    public record PriceChangeResponse(
            Long id, Long planId, String planCode, String planName,
            BigDecimal oldPrice, BigDecimal newPrice, BigDecimal oldJoiningFee, BigDecimal newJoiningFee,
            BigDecimal changePct, LocalDate effectiveDate, ExistingMemberPolicy existingMemberPolicy,
            LocalDate protectedUntil, String reason, PriceChangeSource source, PriceChangeStatus status,
            Instant createdAt, Long createdBy, String createdByName, Instant appliedAt, Integer protectedCount,
            int notifiedCount, Instant cancelledAt, String cancelledByName, String cancelReason) {

        public static PriceChangeResponse from(PlanPriceChange c, String createdByName, String cancelledByName) {
            return new PriceChangeResponse(c.getId(), c.getPlan().getId(), c.getPlan().getCode(), c.getPlan().getName(),
                    c.getOldPrice(), c.getNewPrice(), c.getOldJoiningFee(), c.getNewJoiningFee(),
                    pct(c.getOldPrice(), c.getNewPrice()), c.getEffectiveDate(), c.getPolicy(), c.getProtectedUntil(),
                    c.getReason(), c.getSource(), c.getStatus(), c.getCreatedAt(), c.getCreatedBy(), createdByName,
                    c.getAppliedAt(), c.getProtectedCount(), c.getNotifiedCount(), c.getCancelledAt(), cancelledByName,
                    c.getCancelReason());
        }
    }

    /** An autopay member affected by a proposed change, with the projected first debit at the new price. */
    public record AffectedMember(Long subscriptionId, Long memberId, String memberCode, String memberName,
                                 LocalDate termEnd, BigDecimal currentPrice, BigDecimal newRenewalPrice,
                                 PriceBasis renewalBasis, LocalDate firstDebitAtNewPrice) {
    }

    public record PreviewResponse(
            Long planId, String planName, BigDecimal currentPrice, BigDecimal newPrice, BigDecimal currentJoiningFee,
            BigDecimal newJoiningFee, BigDecimal changePct, boolean increase, LocalDate effectiveDate,
            ExistingMemberPolicy existingMemberPolicy, LocalDate protectedUntil, int noticeDays, LocalDate noticeSentOn,
            long openSubscriptions, long autopaySubscriptions, long autopayMembersAffected, List<AffectedMember> autopayMembers,
            List<String> notes) {
    }

    public record PricingSettings(int priceChangeNoticeDays, BigDecimal maxOverrideDiscountPct, String sacCode,
                                  String legalBusinessName) {
    }

    public record RunSummary(int changesApplied, int noticesSent, int failures) {
    }

    public static BigDecimal pct(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() == 0) {
            return null;
        }
        return to.subtract(from).multiply(BigDecimal.valueOf(100)).divide(from, 1, RoundingMode.HALF_UP);
    }
}
