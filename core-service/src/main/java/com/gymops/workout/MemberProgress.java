package com.gymops.workout;

import com.gymops.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A body-composition / fitness measurement, append-only. */
@Getter
@Setter
@Entity
@Table(name = "member_progress")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workout_plan_id")
    private WorkoutPlan workoutPlan;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "weight_kg")
    private BigDecimal weightKg;

    @Column(name = "body_fat_pct")
    private BigDecimal bodyFatPct;

    @Column(name = "muscle_mass_kg")
    private BigDecimal muscleMassKg;

    @Column(name = "chest_cm")
    private BigDecimal chestCm;

    @Column(name = "waist_cm")
    private BigDecimal waistCm;

    @Column(name = "hips_cm")
    private BigDecimal hipsCm;

    @Column(name = "resting_heart_rate")
    private Integer restingHeartRate;

    private String notes;

    @Column(name = "recorded_by")
    private Long recordedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public MemberProgress(Member member, WorkoutPlan plan, Instant recordedAt, Long recordedBy) {
        this.member = member;
        this.workoutPlan = plan;
        this.recordedAt = recordedAt;
        this.recordedBy = recordedBy;
        this.createdAt = recordedAt;
    }
}
