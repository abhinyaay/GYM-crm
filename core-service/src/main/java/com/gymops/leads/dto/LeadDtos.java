package com.gymops.leads.dto;

import com.gymops.leads.Lead;
import com.gymops.leads.LeadActivity;
import com.gymops.leads.LeadActivityType;
import com.gymops.leads.LeadInterest;
import com.gymops.leads.LeadSource;
import com.gymops.leads.LeadStatus;
import com.gymops.leads.LostReason;
import com.gymops.subscription.SubscriptionStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LeadDtos {

    /** Letters (any script), spaces and common name punctuation: keeps links and markup out of public input. */
    public static final String NAME_PATTERN = "^[\\p{L}\\p{M} .'-]{1,80}$";
    public static final String PHONE_PATTERN = "^\\+?[0-9 -]{7,20}$";

    private LeadDtos() {
    }

    /**
     * Public "book a free trial" form. {@code website} is a honeypot: humans never see the field, so a
     * non-empty value marks the submission as automated and it is silently discarded. {@code referralCode}
     * (optional) comes from a member's shared link ({@code /enquire?ref=CODE}).
     */
    public record EnquiryRequest(
            @NotNull Long branchId,
            @NotBlank @Pattern(regexp = NAME_PATTERN, message = "invalid name") String firstName,
            @Pattern(regexp = "^$|" + NAME_PATTERN, message = "invalid name") String lastName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = PHONE_PATTERN, message = "invalid phone number") String phone,
            LeadInterest interest,
            @Size(max = 1000) String message,
            @AssertTrue(message = "consent is required") boolean consent,
            @Size(max = 200) String website,
            @Size(max = 20) String referralCode) {

        /** Without a referral code (the form before {@code ?ref=} links existed). */
        public EnquiryRequest(Long branchId, String firstName, String lastName, String email, String phone,
                              LeadInterest interest, String message, boolean consent, String website) {
            this(branchId, firstName, lastName, email, phone, interest, message, consent, website, null);
        }

        public boolean hasReferralCode() {
            return referralCode != null && !referralCode.isBlank();
        }
    }

    public record EnquiryAccepted(String message) {
    }

    public record CreateRequest(
            @NotNull Long branchId,
            @NotBlank @Size(max = 80) String firstName,
            @Size(max = 80) String lastName,
            @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = PHONE_PATTERN, message = "invalid phone number") String phone,
            @NotNull LeadSource source,
            LeadInterest interest,
            Long ownerUserId,
            @Size(max = 1000) String notes) {
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 80) String firstName,
            @Size(max = 80) String lastName,
            @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = PHONE_PATTERN, message = "invalid phone number") String phone,
            @NotNull LeadSource source,
            LeadInterest interest,
            @Size(max = 1000) String notes) {
    }

    public record AssignRequest(Long ownerUserId) {
    }

    public record StatusRequest(@NotNull LeadStatus status, LostReason lostReason, @Size(max = 500) String note) {
    }

    public record ActivityRequest(@NotNull LeadActivityType type, @NotBlank @Size(max = 2000) String body) {
    }

    public record FollowUpRequest(@NotNull Instant dueAt, @NotBlank @Size(max = 2000) String body) {
    }

    public record CompleteFollowUpRequest(@Size(max = 2000) String outcome) {
    }

    public record StartTrialRequest(@NotNull Long planId, LocalDate startDate) {
    }

    public record ConvertRequest(@NotNull Long memberId) {
    }

    public record LeadResponse(
            Long id, Long branchId, String branchName, String firstName, String lastName, String fullName,
            String email, String phone, LeadSource source, LeadInterest interest, LeadStatus status,
            Long ownerUserId, String ownerName, Instant nextFollowUpAt, boolean followUpOverdue,
            Long trialSubscriptionId, LocalDate trialEndsOn, Long memberId, Instant convertedAt,
            LostReason lostReason, String notes, Instant createdAt, Instant updatedAt, long version) {

        public static LeadResponse from(Lead l, Map<Long, String> names, Instant now) {
            return new LeadResponse(l.getId(), l.getBranch().getId(), l.getBranch().getName(), l.getFirstName(),
                    l.getLastName(), l.fullName(), l.getEmail(), l.getPhone(), l.getSource(), l.getInterest(),
                    l.getStatus(), l.getOwnerUserId(), l.getOwnerUserId() == null ? null : names.get(l.getOwnerUserId()),
                    l.getNextFollowUpAt(), l.getNextFollowUpAt() != null && l.getNextFollowUpAt().isBefore(now),
                    l.getTrialSubscriptionId(), l.getTrialEndsOn(), l.getMemberId(), l.getConvertedAt(),
                    l.getLostReason(), l.getNotes(), l.getCreatedAt(), l.getUpdatedAt(), l.getVersion());
        }
    }

    public record ActivityResponse(Long id, LeadActivityType type, String body, Instant dueAt, Instant completedAt,
                                   boolean overdue, Long createdBy, String createdByName, Instant createdAt) {

        public static ActivityResponse from(LeadActivity a, Map<Long, String> names, Instant now) {
            return new ActivityResponse(a.getId(), a.getType(), a.getBody(), a.getDueAt(), a.getCompletedAt(),
                    a.isOverdue(now), a.getCreatedBy(), a.getCreatedBy() == null ? null : names.get(a.getCreatedBy()),
                    a.getCreatedAt());
        }
    }

    /** Lead profile with its timeline and the moves the state machine currently allows. */
    public record LeadDetailResponse(LeadResponse lead, List<ActivityResponse> activities,
                                     Set<LeadStatus> allowedStatusChanges, boolean canStartTrial, boolean canConvert,
                                     MemberLink member) {
    }

    public record MemberLink(Long id, String memberCode, String fullName) {
    }

    public record TrialResponse(LeadResponse lead, Long memberId, Long subscriptionId, SubscriptionStatus subscriptionStatus,
                                LocalDate startDate, LocalDate endDate, BigDecimal price, Long invoiceId,
                                boolean paymentDue, boolean existingMemberLinked) {
    }

    public record FollowUpItem(Long activityId, Long leadId, String leadName, String phone, LeadStatus leadStatus,
                               Long branchId, Long ownerUserId, String ownerName, String body, Instant dueAt,
                               boolean overdue) {
    }

    public record OwnerOption(Long userId, String fullName) {
    }

    public record PipelineSummary(Map<LeadStatus, Long> byStatus, long open, long followUpsDueToday,
                                  long followUpsOverdue, long myFollowUpsDue, long conversionsThisMonth,
                                  long newThisMonth, long trialsEndingSoon) {
    }
}
