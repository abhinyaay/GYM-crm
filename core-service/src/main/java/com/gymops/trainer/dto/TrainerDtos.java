package com.gymops.trainer.dto;

import com.gymops.trainer.AssignmentStatus;
import com.gymops.trainer.MemberTrainer;
import com.gymops.trainer.SessionStatus;
import com.gymops.trainer.Trainer;
import com.gymops.trainer.TrainerAvailability;
import com.gymops.trainer.TrainerStatus;
import com.gymops.trainer.TrainingSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TrainerDtos {

    private TrainerDtos() {
    }

    public record TrainerRequest(
            @NotNull Long userId,
            @NotNull Long homeBranchId,
            @NotBlank @Size(max = 20) String employeeCode,
            @Size(max = 1000) String bio,
            @Size(max = 500) String certifications,
            @Min(0) @Max(60) int yearsOfExperience,
            @DecimalMin("0.00") BigDecimal hourlyRate,
            @Min(1) @Max(200) Integer maxActiveClients,
            @Size(max = 10) Set<@NotBlank @Size(max = 50) String> specializations) {
    }

    public record TrainerUpdateRequest(
            @NotNull Long homeBranchId,
            @Size(max = 1000) String bio,
            @Size(max = 500) String certifications,
            @Min(0) @Max(60) int yearsOfExperience,
            @DecimalMin("0.00") BigDecimal hourlyRate,
            @Min(1) @Max(200) Integer maxActiveClients,
            TrainerStatus status,
            @Size(max = 10) Set<@NotBlank @Size(max = 50) String> specializations) {
    }

    public record AvailabilityRequest(@NotNull @Size(max = 42) List<@Valid Slot> slots) {
        public record Slot(@NotNull Long branchId, @Min(1) @Max(7) int dayOfWeek, @NotNull LocalTime startTime,
                           @NotNull LocalTime endTime) {
            @AssertTrue(message = "startTime must be before endTime")
            public boolean isOrdered() {
                return startTime == null || endTime == null || startTime.isBefore(endTime);
            }
        }
    }

    public record AssignMemberRequest(@NotNull Long memberId, @Size(max = 300) String goal) {
    }

    public record SessionRequest(@NotNull Long memberId, @NotNull @Future Instant startsAt,
                                 @Min(15) @Max(180) int durationMinutes, @Size(max = 1000) String notes) {
    }

    public record SessionStatusRequest(@NotNull SessionStatus status, @Size(max = 1000) String notes) {
    }

    public record SlotResponse(Long branchId, int dayOfWeek, LocalTime startTime, LocalTime endTime) {
        public static SlotResponse from(TrainerAvailability a) {
            return new SlotResponse(a.getBranch().getId(), a.getDayOfWeek(), a.getStartTime(), a.getEndTime());
        }
    }

    public record TrainerResponse(Long id, Long userId, String fullName, String email, Long homeBranchId,
                                  String employeeCode, String bio, String certifications, int yearsOfExperience,
                                  BigDecimal hourlyRate, int maxActiveClients, TrainerStatus status,
                                  Set<String> specializations, List<SlotResponse> availability) {
        public static TrainerResponse from(Trainer t) {
            return new TrainerResponse(t.getId(), t.getUser().getId(), t.getUser().getFullName(), t.getUser().getEmail(),
                    t.getHomeBranch().getId(), t.getEmployeeCode(), t.getBio(), t.getCertifications(),
                    t.getYearsOfExperience(), t.getHourlyRate(), t.getMaxActiveClients(), t.getStatus(),
                    Set.copyOf(t.getSpecializations()), t.getAvailability().stream().map(SlotResponse::from).toList());
        }
    }

    public record AssignmentResponse(Long id, Long memberId, String memberCode, String memberName, Long trainerId,
                                     String trainerName, AssignmentStatus status, String goal, Instant assignedAt,
                                     Instant endedAt) {
        public static AssignmentResponse from(MemberTrainer a) {
            return new AssignmentResponse(a.getId(), a.getMember().getId(), a.getMember().getMemberCode(),
                    a.getMember().fullName(), a.getTrainer().getId(), a.getTrainer().getUser().getFullName(),
                    a.getStatus(), a.getGoal(), a.getAssignedAt(), a.getEndedAt());
        }
    }

    public record SessionResponse(Long id, Long trainerId, Long memberId, String memberName, Long branchId,
                                  Instant startsAt, Instant endsAt, SessionStatus status, String notes) {
        public static SessionResponse from(TrainingSession s) {
            return new SessionResponse(s.getId(), s.getTrainer().getId(), s.getMember().getId(), s.getMember().fullName(),
                    s.getBranch().getId(), s.getStartsAt(), s.getEndsAt(), s.getStatus(), s.getNotes());
        }
    }

    /** Raw operational performance counts for one trainer (utilisation analytics live in the analytics service). */
    public record PerformanceResponse(Long trainerId, Instant from, Instant to, long activeClients,
                                      Map<SessionStatus, Long> sessionsByStatus, double completionRate,
                                      double noShowRate) {
    }
}
