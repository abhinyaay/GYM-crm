package com.gymops.trainer;

import com.gymops.branch.GymBranch;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.common.persistence.BaseEntity;
import com.gymops.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A booked 1:1 session. Double-booking is prevented by GiST exclusion constraints on both parties. */
@Getter
@Entity
@Table(name = "training_sessions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrainingSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trainer_id")
    private Trainer trainer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id")
    private GymBranch branch;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status = SessionStatus.SCHEDULED;

    private String notes;

    public TrainingSession(Trainer trainer, Member member, GymBranch branch, Instant startsAt, Instant endsAt, String notes) {
        this.trainer = trainer;
        this.member = member;
        this.branch = branch;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.notes = notes;
    }

    public void transitionTo(SessionStatus target, String notes, Instant now) {
        if (status != SessionStatus.SCHEDULED || target == SessionStatus.SCHEDULED) {
            throw new InvalidStateTransitionException("session", status, "move to " + target);
        }
        if ((target == SessionStatus.COMPLETED || target == SessionStatus.NO_SHOW) && now.isBefore(startsAt)) {
            throw new BusinessRuleException("A session can only be marked " + target + " after it has started");
        }
        this.status = target;
        if (notes != null) {
            this.notes = notes;
        }
    }
}
