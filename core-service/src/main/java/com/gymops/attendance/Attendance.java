package com.gymops.attendance;

import com.gymops.branch.GymBranch;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.member.Member;
import com.gymops.subscription.Subscription;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One gym visit. An open visit (no check-out) is unique per member, enforced by a partial unique index. */
@Getter
@Entity
@Table(name = "attendance")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id")
    private GymBranch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id")
    private Subscription subscription;

    @Column(name = "check_in_time", nullable = false)
    private Instant checkInTime;

    @Column(name = "check_out_time")
    private Instant checkOutTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_in_method", nullable = false)
    private CheckInMethod checkInMethod;

    @Column(name = "checked_in_by")
    private Long checkedInBy;

    @Column(name = "auto_checked_out", nullable = false)
    private boolean autoCheckedOut;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Attendance(Member member, GymBranch branch, Subscription subscription, Instant checkInTime,
                      CheckInMethod method, Long checkedInBy) {
        this.member = member;
        this.branch = branch;
        this.subscription = subscription;
        this.checkInTime = checkInTime;
        this.checkInMethod = method;
        this.checkedInBy = checkedInBy;
        this.createdAt = checkInTime;
    }

    public boolean isOpen() {
        return checkOutTime == null;
    }

    public void checkOut(Instant now) {
        if (!isOpen()) {
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION, "Visit " + id + " is already checked out");
        }
        this.checkOutTime = now.isBefore(checkInTime) ? checkInTime : now;
    }

    /** Closes a visit the member forgot to check out of, assuming a typical visit length. */
    public void autoCheckOut(Duration assumedVisitDuration) {
        this.checkOutTime = checkInTime.plus(assumedVisitDuration);
        this.autoCheckedOut = true;
    }

    public Long durationMinutes() {
        return checkOutTime == null ? null : Duration.between(checkInTime, checkOutTime).toMinutes();
    }
}
