package com.gymops.complaint;

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

@Getter
@Entity
@Table(name = "complaints")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Complaint extends BaseEntity {

    public static final int MAX_ESCALATION_LEVEL = 3;

    @Column(name = "ticket_number", nullable = false, unique = true, updatable = false)
    private String ticketNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id")
    private GymBranch branch;

    @Column(name = "raised_by", nullable = false)
    private Long raisedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ComplaintCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ComplaintPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ComplaintStatus status = ComplaintStatus.OPEN;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String description;

    @Column(name = "assigned_to")
    private Long assignedTo;

    private String resolution;

    @Column(name = "escalation_level", nullable = false)
    private short escalationLevel;

    @Column(name = "sla_due_at", nullable = false)
    private Instant slaDueAt;

    @Column(name = "first_response_at")
    private Instant firstResponseAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    public Complaint(String ticketNumber, Member member, GymBranch branch, Long raisedBy, ComplaintCategory category,
                     ComplaintPriority priority, String subject, String description, Instant slaDueAt) {
        this.ticketNumber = ticketNumber;
        this.member = member;
        this.branch = branch;
        this.raisedBy = raisedBy;
        this.category = category;
        this.priority = priority;
        this.subject = subject;
        this.description = description;
        this.slaDueAt = slaDueAt;
    }

    public void assignTo(Long userId, Instant now) {
        if (status == ComplaintStatus.CLOSED) {
            throw new InvalidStateTransitionException("complaint", status, "assign");
        }
        this.assignedTo = userId;
        if (status == ComplaintStatus.OPEN || status == ComplaintStatus.REOPENED) {
            this.status = ComplaintStatus.IN_PROGRESS;
        }
        markResponded(now);
    }

    public void transitionTo(ComplaintStatus target, String resolutionText, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException("complaint", status, "move to " + target);
        }
        if (target == ComplaintStatus.RESOLVED) {
            if (resolutionText == null || resolutionText.isBlank()) {
                throw new BusinessRuleException("A resolution is required to resolve a complaint");
            }
            this.resolution = resolutionText;
            this.resolvedAt = now;
        }
        if (target == ComplaintStatus.CLOSED) {
            this.closedAt = now;
        }
        if (target == ComplaintStatus.REOPENED) {
            this.resolvedAt = null;
        }
        this.status = target;
        markResponded(now);
    }

    /** Raises the escalation level and restarts the SLA clock; used manually and by the SLA job. */
    public void escalate(Instant newSlaDueAt, Instant now) {
        if (!ComplaintStatus.UNRESOLVED.contains(status)) {
            throw new InvalidStateTransitionException("complaint", status, "escalate");
        }
        if (escalationLevel >= MAX_ESCALATION_LEVEL) {
            throw new BusinessRuleException("Complaint is already at the highest escalation level");
        }
        escalationLevel++;
        status = ComplaintStatus.ESCALATED;
        if (priority.ordinal() < ComplaintPriority.CRITICAL.ordinal()) {
            priority = ComplaintPriority.values()[priority.ordinal() + 1];
        }
        slaDueAt = newSlaDueAt;
    }

    public void restartSla(Instant newSlaDueAt) {
        this.slaDueAt = newSlaDueAt;
    }

    public void markResponded(Instant now) {
        if (firstResponseAt == null) {
            firstResponseAt = now;
        }
    }

    public boolean isSlaBreached(Instant now) {
        return ComplaintStatus.UNRESOLVED.contains(status) && now.isAfter(slaDueAt);
    }
}
