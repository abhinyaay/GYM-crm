package com.gymops.growth.campaign;

import com.gymops.payment.DiscountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class CampaignDtos {

    private CampaignDtos() {
    }

    /**
     * Campaign definition. Segment parameters: CHURN_RISK needs {@code churnBand}; EXPIRING_NO_AUTOPAY,
     * NO_RECENT_VISIT and TRIAL_NOT_CONVERTED need {@code segmentDays}; LAPSED needs {@code segmentDays}
     * (min days since expiry) and {@code segmentDaysMax}. {@code cooldownDays} / {@code attributionDays}
     * default to the {@code app.growth.campaigns} settings.
     */
    public record CampaignRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            Long branchId,
            @NotNull CampaignSegment segment,
            ChurnBand churnBand,
            @Min(1) @Max(365) Integer segmentDays,
            @Min(1) @Max(365) Integer segmentDaysMax,
            @NotBlank @Size(max = 200) String titleTemplate,
            @NotBlank @Size(max = 1500) String messageTemplate,
            boolean offerEnabled,
            DiscountType offerDiscountType,
            @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal offerValue,
            @Min(1) @Max(365) Integer offerValidDays,
            CampaignSchedule schedule,
            @Min(1) @Max(7) Integer scheduleDayOfWeek,
            @Min(0) @Max(365) Integer cooldownDays,
            @Min(1) @Max(180) Integer attributionDays) {
    }

    public record CampaignStats(long sent, long uniqueMembers, long converted, BigDecimal revenue, long offersIssued,
                                long offersRedeemed, double conversionRate, long runs) {

        public static final CampaignStats EMPTY = new CampaignStats(0, 0, 0, BigDecimal.ZERO, 0, 0, 0, 0);
    }

    public record CampaignResponse(Long id, String name, String description, Long branchId, String branchName,
                                   CampaignSegment segment, ChurnBand churnBand, Integer segmentDays,
                                   Integer segmentDaysMax, String segmentDescription, String titleTemplate,
                                   String messageTemplate, boolean offerEnabled, DiscountType offerDiscountType,
                                   BigDecimal offerValue, Integer offerValidDays, CampaignSchedule schedule,
                                   Integer scheduleDayOfWeek, CampaignStatus status, int cooldownDays,
                                   int attributionDays, Instant nextRunAt, Instant lastRunAt, Instant createdAt,
                                   Instant updatedAt, long version, CampaignStats stats) {
    }

    public record PreviewRecipient(Long memberId, String memberCode, String name, String branchName, String planName,
                                   LocalDate endDate, boolean inCooldown, String title, String body) {
    }

    public record PreviewResponse(String segmentDescription, int audienceSize, boolean truncated, int inCooldown,
                                  int eligible, List<PreviewRecipient> sample, List<String> warnings) {
    }

    public record RunResponse(Long id, Long campaignId, RunTrigger triggerType, RunStatus status, int audienceSize,
                              int sent, int skipped, int failed, String error, Instant startedAt, Instant finishedAt) {
    }

    public record RecipientResponse(Long id, Long runId, Long memberId, String memberCode, String memberName,
                                    String branchName, Instant sentAt, String offerCode, Instant attributionEndsAt,
                                    Instant convertedAt, BigDecimal conversionAmount, boolean offerRedeemed) {
    }
}
