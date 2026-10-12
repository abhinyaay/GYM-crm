package com.gymops.growth;

import com.gymops.growth.referral.RewardType;
import com.gymops.payment.DiscountType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Referral programme and retention-campaign settings ({@code app.growth.*}).
 *
 * @param enabled  master switch: when false the event listeners and the scheduled job do nothing
 * @param jobCron  when the growth job runs (reward release, scheduled campaigns, conversion sweep), business timezone
 */
@Validated
@ConfigurationProperties(prefix = "app.growth")
public record GrowthProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 10 * * * *") @NotBlank String jobCron,
        @Valid @NotNull @DefaultValue Referral referral,
        @Valid @NotNull @DefaultValue Campaigns campaigns) {

    /**
     * @param referrerCredit       store credit the referrer earns per converted referral
     * @param refereeRewardType    welcome reward for the referred member: a personal discount code or store credit
     * @param refereeDiscountType  PERCENTAGE or FIXED_AMOUNT (discount-code rewards only)
     * @param refereeRewardValue   percentage / rupee amount of the welcome code, or the credit amount
     * @param refereeCodeValidDays validity of the welcome code once issued
     * @param holdDays             rewards are held this long after the qualifying payment; a refund or cancellation
     *                             inside the window reverses them
     * @param monthlyCap           referrer rewards per referrer per calendar month (business timezone)
     * @param attachGraceDays      a code may still be attached at the desk this many days after the referee's first
     *                             paid membership (e.g. the code was mentioned only after paying)
     */
    public record Referral(
            @DefaultValue("500.00") @NotNull @DecimalMin("1.00") BigDecimal referrerCredit,
            @DefaultValue("DISCOUNT_CODE") @NotNull RewardType refereeRewardType,
            @DefaultValue("PERCENTAGE") @NotNull DiscountType refereeDiscountType,
            @DefaultValue("10") @NotNull @DecimalMin("0.01") BigDecimal refereeRewardValue,
            @DefaultValue("90") @Min(1) @Max(365) int refereeCodeValidDays,
            @DefaultValue("14") @Min(0) @Max(90) int holdDays,
            @DefaultValue("5") @Min(1) @Max(1000) int monthlyCap,
            @DefaultValue("7") @Min(0) @Max(30) int attachGraceDays) {

        @AssertTrue(message = "a percentage welcome discount cannot exceed 100")
        public boolean isRefereeValueValid() {
            return refereeRewardType != RewardType.DISCOUNT_CODE || refereeDiscountType != DiscountType.PERCENTAGE
                    || refereeRewardValue == null || refereeRewardValue.compareTo(BigDecimal.valueOf(100)) <= 0;
        }
    }

    /**
     * @param attributionDays default attribution window: a purchase this many days after a message counts as a conversion
     * @param cooldownDays    default: a member is not messaged again by the same campaign within this many days
     * @param sendHour        local hour at which scheduled campaigns go out
     * @param maxAudience     upper bound on recipients per run (protects the outbox from runaway segments)
     * @param previewSample   members shown in a preview
     */
    public record Campaigns(
            @DefaultValue("30") @Min(1) @Max(180) int attributionDays,
            @DefaultValue("14") @Min(0) @Max(365) int cooldownDays,
            @DefaultValue("10") @Min(0) @Max(23) int sendHour,
            @DefaultValue("2000") @Min(1) @Max(50000) int maxAudience,
            @DefaultValue("5") @Min(1) @Max(20) int previewSample) {
    }
}
