package com.gymops.plan;

import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.common.persistence.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "membership_plans")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MembershipPlan extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false)
    private BillingPeriod billingPeriod;

    @Column(name = "duration_months")
    private Integer durationMonths;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private String currency;

    @Column(name = "joining_fee", nullable = false)
    private BigDecimal joiningFee = BigDecimal.ZERO;

    // --- access restrictions ---
    @Column(name = "all_branch_access", nullable = false)
    private boolean allBranchAccess;

    @Column(name = "access_start_time")
    private LocalTime accessStartTime;

    @Column(name = "access_end_time")
    private LocalTime accessEndTime;

    @Column(name = "max_visits_per_month")
    private Integer maxVisitsPerMonth;

    /** How many times a member may enter per day (gate or desk): null = unlimited. */
    @Column(name = "max_entries_per_day")
    private Integer maxEntriesPerDay;

    /** Group-class entitlement per calendar month: null = unlimited, 0 = no classes included. */
    @Column(name = "class_credits_per_month")
    private Integer classCreditsPerMonth;

    // --- freeze policy ---
    @Column(name = "freeze_allowed", nullable = false)
    private boolean freezeAllowed;

    @Column(name = "min_freeze_days", nullable = false)
    private int minFreezeDays;

    @Column(name = "max_freeze_days", nullable = false)
    private int maxFreezeDays;

    @Column(name = "max_freeze_count", nullable = false)
    private int maxFreezeCount;

    // --- cancellation policy ---
    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_policy", nullable = false)
    private CancellationPolicy cancellationPolicy;

    @Column(name = "cooling_off_days", nullable = false)
    private int coolingOffDays;

    @Column(name = "cancellation_fee", nullable = false)
    private BigDecimal cancellationFee = BigDecimal.ZERO;

    @Column(name = "grace_period_days", nullable = false)
    private int gracePeriodDays;

    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanStatus status = PlanStatus.DRAFT;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_benefits", joinColumns = @JoinColumn(name = "plan_id"))
    @OrderColumn(name = "position")
    @Column(name = "benefit", nullable = false)
    private List<String> benefits = new ArrayList<>();

    public MembershipPlan(String code) {
        this.code = code.trim().toUpperCase();
    }

    /** Inclusive end date of a term starting on {@code start}. */
    public LocalDate endDateFor(LocalDate start) {
        if (billingPeriod == BillingPeriod.CUSTOM) {
            return start.plusDays(durationDays - 1L);
        }
        return start.plusMonths(durationMonths).minusDays(1);
    }

    public boolean isActive() {
        return status == PlanStatus.ACTIVE;
    }

    public boolean isAccessibleAt(LocalTime localTime) {
        if (accessStartTime == null) {
            return true;
        }
        return !localTime.isBefore(accessStartTime) && localTime.isBefore(accessEndTime);
    }

    public void activate() {
        if (status == PlanStatus.ARCHIVED) {
            throw new InvalidStateTransitionException("plan", status, "activate");
        }
        status = PlanStatus.ACTIVE;
    }

    /** Archived plans are no longer sold; existing subscriptions keep running on their snapshot. */
    public void archive() {
        status = PlanStatus.ARCHIVED;
    }

    public void replaceBenefits(List<String> newBenefits) {
        benefits.clear();
        if (newBenefits != null) {
            benefits.addAll(newBenefits);
        }
    }
}
