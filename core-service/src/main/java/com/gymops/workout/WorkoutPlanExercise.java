package com.gymops.workout;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "workout_plan_exercises")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkoutPlanExercise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workout_plan_id")
    private WorkoutPlan workoutPlan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exercise_id")
    private Exercise exercise;

    @Column(name = "day_number", nullable = false)
    private short dayNumber;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private int sets;

    private Integer reps;

    @Column(name = "weight_kg")
    private BigDecimal weightKg;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "rest_seconds")
    private Integer restSeconds;

    private String notes;

    public WorkoutPlanExercise(Exercise exercise, int dayNumber, int position, int sets, Integer reps,
                               BigDecimal weightKg, Integer durationSeconds, Integer restSeconds, String notes) {
        this.exercise = exercise;
        this.dayNumber = (short) dayNumber;
        this.position = position;
        this.sets = sets;
        this.reps = reps;
        this.weightKg = weightKg;
        this.durationSeconds = durationSeconds;
        this.restSeconds = restSeconds;
        this.notes = notes;
    }

    void attachTo(WorkoutPlan plan) {
        this.workoutPlan = plan;
    }

    WorkoutPlanExercise copy() {
        return new WorkoutPlanExercise(exercise, dayNumber, position, sets, reps, weightKg, durationSeconds, restSeconds, notes);
    }
}
