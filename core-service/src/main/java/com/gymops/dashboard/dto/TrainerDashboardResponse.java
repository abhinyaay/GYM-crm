package com.gymops.dashboard.dto;

import com.gymops.trainer.dto.TrainerDtos.SessionResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record TrainerDashboardResponse(Long trainerId, String trainerName, long assignedMembers, int maxActiveClients,
                                       List<SessionResponse> todaysSessions, long activeWorkoutPlans,
                                       List<ClientProgress> memberProgress) {

    /** Change between a client's first and latest progress measurement. */
    public record ClientProgress(Long memberId, String memberCode, String memberName, String workoutPlan,
                                 LocalDate lastVisit, BigDecimal startWeightKg, BigDecimal latestWeightKg,
                                 BigDecimal weightChangeKg, Instant lastMeasuredAt) {
    }
}
