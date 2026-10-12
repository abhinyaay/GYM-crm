package com.gymops.leads;

import com.gymops.common.exception.BusinessRuleException;
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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Append-only timeline entry on a lead; FOLLOW_UP entries are tasks with a due time and completion. */
@Getter
@Entity
@Table(name = "lead_activities")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeadActivity {

    public static final int MAX_BODY = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id", updatable = false)
    private Lead lead;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private LeadActivityType type;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(name = "due_at", updatable = false)
    private Instant dueAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private LeadActivity(Lead lead, LeadActivityType type, String body, Instant dueAt, Long createdBy, Instant now) {
        this.lead = lead;
        this.type = type;
        this.body = truncate(body);
        this.dueAt = dueAt;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public static LeadActivity log(Lead lead, LeadActivityType type, String body, Long createdBy, Instant now) {
        if (type == LeadActivityType.FOLLOW_UP) {
            throw new BusinessRuleException("Use followUp() for follow-up tasks");
        }
        return new LeadActivity(lead, type, body, null, createdBy, now);
    }

    public static LeadActivity followUp(Lead lead, String body, Instant dueAt, Long createdBy, Instant now) {
        if (dueAt == null) {
            throw new BusinessRuleException("A follow-up needs a due time");
        }
        return new LeadActivity(lead, LeadActivityType.FOLLOW_UP, body, dueAt, createdBy, now);
    }

    public boolean isOpenFollowUp() {
        return type == LeadActivityType.FOLLOW_UP && completedAt == null;
    }

    public void complete(Instant now) {
        if (type != LeadActivityType.FOLLOW_UP) {
            throw new BusinessRuleException("Only follow-ups can be completed");
        }
        if (completedAt != null) {
            throw new BusinessRuleException("Follow-up is already completed");
        }
        this.completedAt = now;
    }

    public boolean isOverdue(Instant now) {
        return isOpenFollowUp() && dueAt.isBefore(now);
    }

    private static String truncate(String s) {
        String text = s == null ? "" : s.trim();
        return text.length() > MAX_BODY ? text.substring(0, MAX_BODY) : text;
    }
}
