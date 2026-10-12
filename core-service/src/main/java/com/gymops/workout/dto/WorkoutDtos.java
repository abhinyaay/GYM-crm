package com.gymops.workout.dto;

import com.gymops.workout.Difficulty;
import com.gymops.workout.Exercise;
import com.gymops.workout.MemberProgress;
import com.gymops.workout.WorkoutGoal;
import com.gymops.workout.WorkoutPlan;
import com.gymops.workout.WorkoutPlanExercise;
import com.gymops.workout.WorkoutPlanStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class WorkoutDtos {

    private WorkoutDtos() {
    }

    public record ExerciseRequest(@NotBlank @Size(max = 100) String name,
                                  @NotBlank @Pattern(regexp = "^[A-Za-z_]{3,30}$") String muscleGroup,
                                  @Size(max = 60) String equipment, @Size(max = 1000) String description) {
    }

    public record ExerciseResponse(Long id, String name, String muscleGroup, String equipment, String description) {
        public static ExerciseResponse from(Exercise e) {
            return new ExerciseResponse(e.getId(), e.getName(), e.getMuscleGroup(), e.getEquipment(), e.getDescription());
        }
    }

    public record PlanExerciseRequest(@NotNull Long exerciseId, @Min(1) @Max(7) int dayNumber, @Min(1) @Max(20) int sets,
                                      @Min(1) @Max(100) Integer reps, @DecimalMin("0") @DecimalMax("500") BigDecimal weightKg,
                                      @Min(1) @Max(7200) Integer durationSeconds, @Min(0) @Max(600) Integer restSeconds,
                                      @Size(max = 300) String notes) {
        @AssertTrue(message = "either reps or durationSeconds is required")
        public boolean isMeasured() {
            return reps != null || durationSeconds != null;
        }
    }

    /** Create/update a template, or update a member's personal plan. */
    public record WorkoutPlanRequest(@NotBlank @Size(max = 120) String name, @Size(max = 1000) String description,
                                     @NotNull WorkoutGoal goal, @NotNull Difficulty difficulty,
                                     @Min(1) @Max(52) int durationWeeks, @Size(max = 2000) String trainerNotes,
                                     @NotNull @Size(max = 60) List<@Valid PlanExerciseRequest> exercises) {
    }

    public record AssignPlanRequest(@NotNull Long memberId, LocalDate startDate) {
    }

    public record ProgressRequest(
            @PastOrPresent Instant recordedAt,
            @DecimalMin("20") @DecimalMax("400") BigDecimal weightKg,
            @DecimalMin("2") @DecimalMax("70") BigDecimal bodyFatPct,
            @DecimalMin("5") @DecimalMax("200") BigDecimal muscleMassKg,
            @DecimalMin("30") @DecimalMax("250") BigDecimal chestCm,
            @DecimalMin("30") @DecimalMax("250") BigDecimal waistCm,
            @DecimalMin("30") @DecimalMax("250") BigDecimal hipsCm,
            @Min(25) @Max(220) Integer restingHeartRate,
            @Size(max = 1000) String notes) {
    }

    public record PlanExerciseResponse(Long exerciseId, String exerciseName, String muscleGroup, int dayNumber,
                                       int position, int sets, Integer reps, BigDecimal weightKg,
                                       Integer durationSeconds, Integer restSeconds, String notes) {
        static PlanExerciseResponse from(WorkoutPlanExercise e) {
            return new PlanExerciseResponse(e.getExercise().getId(), e.getExercise().getName(),
                    e.getExercise().getMuscleGroup(), e.getDayNumber(), e.getPosition(), e.getSets(), e.getReps(),
                    e.getWeightKg(), e.getDurationSeconds(), e.getRestSeconds(), e.getNotes());
        }
    }

    public record WorkoutPlanResponse(Long id, String name, String description, WorkoutGoal goal, Difficulty difficulty,
                                      int durationWeeks, boolean template, Long memberId, Long trainerId,
                                      Long sourceTemplateId, WorkoutPlanStatus status, LocalDate startDate,
                                      LocalDate endDate, String trainerNotes, List<PlanExerciseResponse> exercises,
                                      long version) {
        public static WorkoutPlanResponse from(WorkoutPlan p) {
            return new WorkoutPlanResponse(p.getId(), p.getName(), p.getDescription(), p.getGoal(), p.getDifficulty(),
                    p.getDurationWeeks(), p.isTemplate(), p.getMember() == null ? null : p.getMember().getId(),
                    p.getTrainer().getId(), p.getSourceTemplate() == null ? null : p.getSourceTemplate().getId(),
                    p.getStatus(), p.getStartDate(), p.getEndDate(), p.getTrainerNotes(),
                    p.getExercises().stream().map(PlanExerciseResponse::from).toList(), p.getVersion());
        }
    }

    public record ProgressResponse(Long id, Long memberId, Long workoutPlanId, Instant recordedAt, BigDecimal weightKg,
                                   BigDecimal bodyFatPct, BigDecimal muscleMassKg, BigDecimal chestCm, BigDecimal waistCm,
                                   BigDecimal hipsCm, Integer restingHeartRate, String notes, Long recordedBy) {
        public static ProgressResponse from(MemberProgress p) {
            return new ProgressResponse(p.getId(), p.getMember().getId(),
                    p.getWorkoutPlan() == null ? null : p.getWorkoutPlan().getId(), p.getRecordedAt(), p.getWeightKg(),
                    p.getBodyFatPct(), p.getMuscleMassKg(), p.getChestCm(), p.getWaistCm(), p.getHipsCm(),
                    p.getRestingHeartRate(), p.getNotes(), p.getRecordedBy());
        }
    }
}
