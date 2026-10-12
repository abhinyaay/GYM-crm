package com.gymops.growth.referral;

import com.gymops.payment.DiscountType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ReferralDtos {

    private ReferralDtos() {
    }

    /** Front desk: attach a referral code to a new member or an open lead (exactly one of the two). */
    public record AttachRequest(@NotBlank @Size(max = 20) String code, Long memberId, Long leadId) {

        @AssertTrue(message = "give exactly one of memberId or leadId")
        public boolean isTargetValid() {
            return (memberId == null) != (leadId == null);
        }
    }

    /** A newly registered member entering the code they were given. */
    public record ClaimRequest(@NotBlank @Size(max = 20) String code) {
    }

    /** Public lookup: only the referrer's first name and the offer (no other personal data). */
    public record PublicReferral(String code, String referrerFirstName, String headline, String offer) {
    }

    public record RewardView(Long id, Long referralId, Beneficiary beneficiary, RewardType rewardType, BigDecimal amount,
                             DiscountType discountType, RewardStatus status, String description, Instant availableAt,
                             Instant releasedAt, Instant reversedAt, String discountCode, LocalDate codeValidUntil,
                             Boolean codeUsed) {
    }

    /** One of my referrals as the referrer sees it (referee shown as "Rahul S."). */
    public record MyReferral(Long id, String refereeName, ReferralChannel channel, ReferralStatus status,
                             RejectionReason rejectionReason, RewardSkipReason referrerRewardSkipped, Instant createdAt,
                             Instant convertedAt, Instant holdUntil, RewardView reward) {
    }

    public record MyReferralDashboard(String code, String sharePath, String headline, String offerText,
                                      String referrerRewardText, int holdDays, int monthlyCap, long invited, long joined,
                                      BigDecimal creditEarned, BigDecimal creditPending, List<MyReferral> referrals,
                                      List<RewardView> welcomeRewards, String referredBy, boolean canClaimCode) {
    }

    /** Staff view of a referral. */
    public record ReferralResponse(Long id, Long referrerMemberId, String referrerName, String referrerMemberCode,
                                   String code, Long refereeMemberId, String refereeMemberCode, Long refereeLeadId,
                                   String refereeName, Long branchId, String branchName, ReferralChannel channel,
                                   ReferralStatus status, RejectionReason rejectionReason,
                                   RewardSkipReason referrerRewardSkipped, Instant createdAt, Instant convertedAt,
                                   Instant holdUntil, Instant reversedAt, String reversalReason,
                                   RewardStatus referrerRewardStatus, BigDecimal referrerRewardAmount,
                                   RewardStatus refereeRewardStatus) {
    }

    /** Staff view of one member's referral activity (member profile / desk lookup). */
    public record MemberReferralSummary(Long memberId, String code, String sharePath, long referrals, long conversions,
                                        BigDecimal creditEarned, ReferralResponse referredBy) {
    }

    public record ReferralSettings(boolean enabled, BigDecimal referrerCredit, RewardType refereeRewardType,
                                   DiscountType refereeDiscountType, BigDecimal refereeRewardValue,
                                   int refereeCodeValidDays, int holdDays, int monthlyCap, int attachGraceDays,
                                   String referrerRewardText, String refereeRewardText) {
    }

    public record TopReferrer(Long memberId, String name, String memberCode, String code, long referrals,
                              long conversions, BigDecimal creditEarned) {
    }

    public record MonthPoint(String month, long referrals, long conversions) {
    }

    public record RewardTotals(long count, BigDecimal creditAmount, long codes) {
    }

    public record ReferralStats(long total, Map<ReferralStatus, Long> byStatus, double conversionRate,
                                RewardTotals issued, RewardTotals pending, RewardTotals reversed,
                                long cappedReferrals, List<TopReferrer> topReferrers, List<MonthPoint> monthly) {
    }
}
