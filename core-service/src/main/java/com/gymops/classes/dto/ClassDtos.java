package com.gymops.classes.dto;

import com.gymops.classes.BookingStatus;
import com.gymops.classes.ClassBooking;
import com.gymops.classes.ClassCategory;
import com.gymops.classes.ClassIntensity;
import com.gymops.classes.ClassSession;
import com.gymops.classes.ClassSessionStatus;
import com.gymops.classes.ClassType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ClassDtos {

    private ClassDtos() {
    }

    // ------------------------------------------------------------------ class types

    public record ClassTypeRequest(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 1000) String description,
            @NotNull ClassCategory category,
            @NotNull ClassIntensity intensity,
            @Min(15) @Max(180) int defaultDurationMin,
            @Min(1) @Max(200) int defaultCapacity,
            Boolean active) {
    }

    public record ClassTypeResponse(Long id, String name, String description, ClassCategory category,
                                    ClassIntensity intensity, int defaultDurationMin, int defaultCapacity, boolean active) {
        public static ClassTypeResponse from(ClassType t) {
            return new ClassTypeResponse(t.getId(), t.getName(), t.getDescription(), t.getCategory(), t.getIntensity(),
                    t.getDefaultDurationMin(), t.getDefaultCapacity(), t.isActive());
        }
    }

    // ------------------------------------------------------------------ sessions

    /** Creates one class; omitted policy fields fall back to the class type / table defaults. */
    public record SessionRequest(
            @NotNull Long classTypeId,
            @NotNull Long branchId,
            @NotNull Long instructorId,
            @Size(max = 60) String room,
            @NotNull Instant startsAt,
            @Min(15) @Max(180) Integer durationMinutes,
            @Min(1) @Max(200) Integer capacity,
            @Min(0) @Max(100) Integer waitlistCapacity,
            @Min(1) @Max(720) Integer bookingOpensHours,
            @Min(0) @Max(1440) Integer cancelCutoffMinutes) {
    }

    /**
     * Creates a weekly series: the first occurrence's local time (branch time zone) is repeated on the
     * given ISO days of week (1 = Monday) for {@code weeks} weeks starting with the week of {@code session.startsAt}.
     */
    public record SeriesRequest(
            @NotNull @Valid SessionRequest session,
            @NotEmpty @Size(max = 7) Set<@NotNull @Min(1) @Max(7) Integer> daysOfWeek,
            @Min(1) @Max(52) int weeks) {
    }

    public record SessionUpdateRequest(
            @NotNull Long instructorId,
            @Size(max = 60) String room,
            @NotNull Instant startsAt,
            @NotNull @Min(15) @Max(180) Integer durationMinutes,
            @NotNull @Min(1) @Max(200) Integer capacity,
            @NotNull @Min(0) @Max(100) Integer waitlistCapacity,
            @NotNull @Min(1) @Max(720) Integer bookingOpensHours,
            @NotNull @Min(0) @Max(1440) Integer cancelCutoffMinutes) {
    }

    public record CancelSessionRequest(@NotBlank @Size(max = 300) String reason) {
    }

    public record CancelSeriesRequest(@NotNull LocalDate from, @NotBlank @Size(max = 300) String reason) {
    }

    /** Booking counts for one session. {@code booked} counts every seat holder (BOOKED, ATTENDED, NO_SHOW). */
    public record Counts(long booked, long waitlisted, long attended, long noShows, long lateCancelled) {
        public static final Counts EMPTY = new Counts(0, 0, 0, 0, 0);
    }

    public record SessionResponse(
            Long id, Long classTypeId, String classTypeName, ClassCategory category, ClassIntensity intensity,
            Long branchId, String branchName, Long instructorId, String instructorName, String room,
            Instant startsAt, Instant endsAt, long durationMinutes, int capacity, int waitlistCapacity,
            int bookingOpensHours, int cancelCutoffMinutes, Instant bookingOpensAt, Instant cancelCutoffAt,
            ClassSessionStatus status, UUID seriesId, String cancellationReason,
            long booked, long waitlisted, long spotsLeft, long attended, long noShows,
            Long myBookingId, BookingStatus myBookingStatus) {

        public static SessionResponse from(ClassSession s, Counts c, ClassBooking mine) {
            return new SessionResponse(s.getId(), s.getClassType().getId(), s.getClassType().getName(),
                    s.getClassType().getCategory(), s.getClassType().getIntensity(), s.getBranch().getId(),
                    s.getBranch().getName(), s.getInstructor().getId(), s.getInstructor().getUser().getFullName(), s.getRoom(),
                    s.getStartsAt(), s.getEndsAt(), s.durationMinutes(), s.getCapacity(), s.getWaitlistCapacity(),
                    s.getBookingOpensHours(), s.getCancelCutoffMinutes(), s.bookingOpensAt(), s.cancelCutoffAt(),
                    s.getStatus(), s.getSeriesId(), s.getCancellationReason(),
                    c.booked(), c.waitlisted(), Math.max(0, s.getCapacity() - c.booked()), c.attended(), c.noShows(),
                    mine == null ? null : mine.getId(), mine == null ? null : mine.getStatus());
        }
    }

    public record RosterEntry(Long bookingId, Long memberId, String memberCode, String memberName, BookingStatus status,
                              Instant bookedAt, Instant promotedAt, Instant cancelledAt, Instant attendedAt,
                              Integer waitlistPosition) {
        public static RosterEntry from(ClassBooking b, Integer waitlistPosition) {
            return new RosterEntry(b.getId(), b.getMember().getId(), b.getMember().getMemberCode(), b.getMember().fullName(),
                    b.getStatus(), b.getBookedAt(), b.getPromotedAt(), b.getCancelledAt(), b.getAttendedAt(), waitlistPosition);
        }
    }

    /**
     * Session with roster. {@code roster} is null for members; the flags tell the UI which actions
     * the caller may take.
     */
    public record SessionDetailResponse(SessionResponse session, List<RosterEntry> roster, boolean canManage,
                                        boolean canMarkAttendance, boolean canBookForMembers) {
    }

    public record SkippedSlot(Instant startsAt, String reason) {
    }

    public record SeriesResponse(UUID seriesId, List<SessionResponse> created, List<SkippedSlot> skipped) {
    }

    public record SeriesCancelResponse(UUID seriesId, int cancelledSessions, int cancelledBookings) {
    }

    // ------------------------------------------------------------------ bookings

    /** {@code memberId} is required for staff and ignored/validated for members booking themselves. */
    public record BookingRequest(Long memberId) {
    }

    public record AttendanceRequest(@NotNull BookingStatus status) {
    }

    public record BookingResponse(
            Long id, Long sessionId, Long memberId, String memberCode, String memberName, BookingStatus status,
            Instant bookedAt, Instant promotedAt, Instant cancelledAt, Instant attendedAt, Integer waitlistPosition,
            String classTypeName, ClassCategory category, Long branchId, String branchName, String instructorName,
            String room, Instant startsAt, Instant endsAt, ClassSessionStatus sessionStatus, Instant cancelCutoffAt) {

        public static BookingResponse from(ClassBooking b, Integer waitlistPosition) {
            ClassSession s = b.getSession();
            return new BookingResponse(b.getId(), s.getId(), b.getMember().getId(), b.getMember().getMemberCode(),
                    b.getMember().fullName(), b.getStatus(), b.getBookedAt(), b.getPromotedAt(), b.getCancelledAt(),
                    b.getAttendedAt(), waitlistPosition, s.getClassType().getName(), s.getClassType().getCategory(),
                    s.getBranch().getId(), s.getBranch().getName(), s.getInstructor().getUser().getFullName(), s.getRoom(),
                    s.getStartsAt(), s.getEndsAt(), s.getStatus(), s.cancelCutoffAt());
        }
    }

    /**
     * Class entitlement for a calendar month. {@code creditsPerMonth}/{@code remaining} are null when
     * unlimited; {@code bookingBlockedUntil} is set while a no-show block is in force.
     */
    public record CreditsResponse(Long memberId, String month, String planCode, String planName, boolean includesClasses,
                                  boolean unlimited, Integer creditsPerMonth, long used, Integer remaining,
                                  long recentNoShows, int noShowThreshold, Instant bookingBlockedUntil) {
    }

    public record JobRunSummary(int sessionsCompleted, int attended, int noShows, int waitlistExpired,
                                int remindersQueued, int failures) {
    }
}
