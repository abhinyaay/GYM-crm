package com.gymops.trainer;

import com.gymops.member.Member;
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

/** Personal-training relationship between a member and a trainer (at most one ACTIVE per member). */
@Getter
@Entity
@Table(name = "member_trainers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberTrainer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trainer_id")
    private Trainer trainer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssignmentStatus status = AssignmentStatus.ACTIVE;

    private String goal;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "assigned_by")
    private Long assignedBy;

    public MemberTrainer(Member member, Trainer trainer, String goal, Instant assignedAt, Long assignedBy) {
        this.member = member;
        this.trainer = trainer;
        this.goal = goal;
        this.assignedAt = assignedAt;
        this.assignedBy = assignedBy;
    }

    public void end(Instant now) {
        this.status = AssignmentStatus.ENDED;
        this.endedAt = now;
    }
}
