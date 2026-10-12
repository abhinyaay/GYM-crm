package com.gymops.attendance.dto;

import com.gymops.attendance.Attendance;
import com.gymops.attendance.CheckInMethod;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;

public final class AttendanceDtos {

    private AttendanceDtos() {
    }

    /** Identify the member by id or by member code (scanned card / QR). */
    public record CheckInRequest(Long memberId, @Size(max = 20) String memberCode, @NotNull Long branchId,
                                 CheckInMethod method) {
        @AssertTrue(message = "exactly one of memberId or memberCode is required")
        public boolean isIdentified() {
            return (memberId == null) != (memberCode == null || memberCode.isBlank());
        }
    }

    public record CheckOutRequest(@NotNull Long memberId) {
    }

    public record AttendanceResponse(Long id, Long memberId, String memberCode, String memberName, Long branchId,
                                     Long subscriptionId, Instant checkInTime, Instant checkOutTime,
                                     CheckInMethod method, boolean autoCheckedOut, Long durationMinutes) {

        public static AttendanceResponse from(Attendance a) {
            return new AttendanceResponse(a.getId(), a.getMember().getId(), a.getMember().getMemberCode(),
                    a.getMember().fullName(), a.getBranch().getId(), a.getSubscription().getId(),
                    a.getCheckInTime(), a.getCheckOutTime(), a.getCheckInMethod(), a.isAutoCheckedOut(),
                    a.durationMinutes());
        }
    }

    public record DailySummary(Long branchId, LocalDate date, long checkIns, long uniqueMembers,
                               long currentlyInside, int capacity, double averageVisitMinutes) {
    }

    public record Occupancy(Long branchId, long currentlyInside, int capacity, double utilisation) {
    }
}
