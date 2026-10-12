package com.gymops.dashboard.dto;

import com.gymops.payment.dto.PaymentResponse;
import com.gymops.subscription.dto.MembershipSummary;
import com.gymops.trainer.dto.TrainerDtos.AssignmentResponse;
import com.gymops.trainer.dto.TrainerDtos.SessionResponse;
import com.gymops.workout.dto.WorkoutDtos.WorkoutPlanResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record MemberDashboardResponse(Long memberId, String memberCode, String name, MembershipSummary membership,
                                      LocalDate expiryDate, Attendance attendance, WorkoutPlanResponse workoutPlan,
                                      AssignmentResponse trainer, List<SessionResponse> upcomingSessions,
                                      List<PaymentResponse> recentPayments, long unreadNotifications) {

    public record Attendance(long visitsThisMonth, long visitsLast30Days, long totalVisits, Instant lastVisit,
                             boolean currentlyCheckedIn) {
    }
}
