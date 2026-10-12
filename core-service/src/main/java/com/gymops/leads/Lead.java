package com.gymops.leads;

import com.gymops.branch.GymBranch;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A prospect in the sales pipeline. All status changes go through intention-revealing methods
 * guarded by {@link LeadStatus#canTransitionTo}; the database CHECKs (lost_reason iff LOST,
 * converted_at iff CONVERTED) mirror the same invariants.
 */
@Getter
@Entity
@Table(name = "leads")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Lead extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", updatable = false)
    private GymBranch branch;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    private String email;

    @Column(nullable = false)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeadSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeadInterest interest = LeadInterest.MEMBERSHIP;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeadStatus status = LeadStatus.NEW;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "next_follow_up_at")
    private Instant nextFollowUpAt;

    @Column(name = "trial_subscription_id")
    private Long trialSubscriptionId;

    @Column(name = "trial_ends_on")
    private LocalDate trialEndsOn;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "converted_at")
    private Instant convertedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "lost_reason")
    private LostReason lostReason;

    private String notes;

    public Lead(GymBranch branch, String firstName, String lastName, String email, String phone, LeadSource source,
                LeadInterest interest, String notes) {
        this.branch = Objects.requireNonNull(branch);
        this.source = Objects.requireNonNull(source);
        updateDetails(firstName, lastName, email, phone, source, interest, notes);
    }

    /** Normalises a phone number for duplicate detection: keeps a leading '+' and the digits. */
    public static String normalizePhone(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        String digits = trimmed.replaceAll("\\D", "");
        return trimmed.startsWith("+") ? "+" + digits : digits;
    }

    public final void updateDetails(String newFirstName, String newLastName, String newEmail, String newPhone,
                                    LeadSource newSource, LeadInterest newInterest, String newNotes) {
        this.firstName = newFirstName.trim();
        this.lastName = blankToNull(newLastName);
        String mail = blankToNull(newEmail);
        this.email = mail == null ? null : mail.toLowerCase();
        this.phone = normalizePhone(newPhone);
        if (newSource != null) {
            this.source = newSource;
        }
        this.interest = newInterest == null ? LeadInterest.MEMBERSHIP : newInterest;
        this.notes = blankToNull(newNotes);
    }

    public String fullName() {
        return lastName == null ? firstName : firstName + " " + lastName;
    }

    public boolean isOpen() {
        return status.isOpen();
    }

    public void assignOwner(Long userId) {
        requireOpen("assign");
        this.ownerUserId = userId;
    }

    /** Fills contact details a repeat enquiry provides without overwriting what staff already captured. */
    public void enrichFromEnquiry(String enquiryEmail, String enquiryLastName) {
        if (email == null && enquiryEmail != null && !enquiryEmail.isBlank()) {
            email = enquiryEmail.trim().toLowerCase();
        }
        if (lastName == null && enquiryLastName != null && !enquiryLastName.isBlank()) {
            lastName = enquiryLastName.trim();
        }
    }

    /** Generic, user-requested move (contacted, tour scheduled, lost, reopen). */
    public void moveTo(LeadStatus target, LostReason reason) {
        if (!LeadStatus.MANUAL_TARGETS.contains(target)) {
            throw new BusinessRuleException("Use the dedicated action to move a lead to " + target);
        }
        switch (target) {
            case LOST -> markLost(reason);
            case NEW -> reopen();
            default -> transition(target);
        }
    }

    /** Logging a real contact with a NEW lead implicitly moves it to CONTACTED; returns true if it did. */
    public boolean recordContact() {
        if (status == LeadStatus.NEW) {
            transition(LeadStatus.CONTACTED);
            return true;
        }
        return false;
    }

    public void markLost(LostReason reason) {
        if (reason == null) {
            throw new BusinessRuleException("A lost reason is required");
        }
        transition(LeadStatus.LOST);
        this.lostReason = reason;
        this.nextFollowUpAt = null;
    }

    public void reopen() {
        transition(LeadStatus.NEW);
        this.lostReason = null;
    }

    public void startTrial(Long subscriptionId, Long trialMemberId, LocalDate endsOn) {
        transition(LeadStatus.TRIAL_ACTIVE);
        this.trialSubscriptionId = Objects.requireNonNull(subscriptionId);
        this.memberId = Objects.requireNonNull(trialMemberId);
        this.trialEndsOn = endsOn;
    }

    public void convert(Long convertedMemberId, Instant now) {
        transition(LeadStatus.CONVERTED);
        this.memberId = Objects.requireNonNull(convertedMemberId);
        this.convertedAt = now;
        this.nextFollowUpAt = null;
    }

    /** Whether an activated subscription is this lead's own trial (which must not count as a conversion). */
    public boolean isTrialSubscription(Long subscriptionId) {
        return trialSubscriptionId != null && trialSubscriptionId.equals(subscriptionId);
    }

    public void scheduleNextFollowUp(Instant earliestOpenFollowUp) {
        this.nextFollowUpAt = isOpen() ? earliestOpenFollowUp : null;
    }

    public void requireOpen(String action) {
        if (!isOpen()) {
            throw new InvalidStateTransitionException("lead", status, action);
        }
    }

    private void transition(LeadStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException("lead", status, "move to " + target);
        }
        this.status = target;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
