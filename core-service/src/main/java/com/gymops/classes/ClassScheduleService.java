package com.gymops.classes;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.branch.BranchService;
import com.gymops.branch.GymBranch;
import com.gymops.branch.StaffAssignmentRepository;
import com.gymops.classes.ClassSession.Policy;
import com.gymops.classes.dto.ClassDtos.CancelSeriesRequest;
import com.gymops.classes.dto.ClassDtos.Counts;
import com.gymops.classes.dto.ClassDtos.RosterEntry;
import com.gymops.classes.dto.ClassDtos.SeriesCancelResponse;
import com.gymops.classes.dto.ClassDtos.SeriesRequest;
import com.gymops.classes.dto.ClassDtos.SeriesResponse;
import com.gymops.classes.dto.ClassDtos.SessionDetailResponse;
import com.gymops.classes.dto.ClassDtos.SessionRequest;
import com.gymops.classes.dto.ClassDtos.SessionResponse;
import com.gymops.classes.dto.ClassDtos.SessionUpdateRequest;
import com.gymops.classes.dto.ClassDtos.SkippedSlot;
import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.security.AccessPolicy;
import com.gymops.security.BranchScope;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import com.gymops.trainer.Trainer;
import com.gymops.trainer.TrainerService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Timetable management: creating single classes and weekly series, editing, cancelling, browsing. */
@Service
@RequiredArgsConstructor
public class ClassScheduleService {

    static final int DEFAULT_WAITLIST = 10;
    static final int DEFAULT_BOOKING_OPENS_HOURS = 168;
    static final int DEFAULT_CANCEL_CUTOFF_MINUTES = 120;
    private static final int MAX_RANGE_DAYS = 92;
    private static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("EEE d MMM HH:mm");

    private final ClassSessionRepository sessionRepository;
    private final ClassBookingRepository bookingRepository;
    private final ClassTypeService typeService;
    private final TrainerService trainerService;
    private final BranchService branchService;
    private final StaffAssignmentRepository staffAssignments;
    private final WaitlistPromoter promoter;
    private final ClassAccess classAccess;
    private final AccessPolicy access;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final ClassesProperties props;
    private final Clock clock;

    // ------------------------------------------------------------------ create

    @Transactional
    public SessionResponse create(SessionRequest r) {
        Template t = template(r);
        Instant end = r.startsAt().plus(t.duration());
        if (!r.startsAt().isAfter(Instant.now(clock))) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "A class must start in the future");
        }
        String clash = clashWith(t.instructor().getId(), t.branch().getId(), t.room(), r.startsAt(), end, null);
        if (clash != null) {
            throw new ConflictException(clash);
        }
        ClassSession session = sessionRepository.saveAndFlush(new ClassSession(t.type(), t.branch(), t.instructor(), t.room(),
                r.startsAt(), end, t.policy(), null));
        audit.record(AuditEvent.of("CLASS_SESSION", session.getId(), "CREATED").branch(t.branch().getId())
                .with("classType", t.type().getName()).with("startsAt", session.getStartsAt().toString()));
        return SessionResponse.from(session, Counts.EMPTY, null);
    }

    /**
     * Expands a weekly pattern into sessions sharing one series id. Slots in the past or clashing with
     * the instructor's other classes (or the room) are skipped and reported, not fatal.
     */
    @Transactional
    public SeriesResponse createSeries(SeriesRequest r) {
        if (r.weeks() > props.maxSeriesWeeks()) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "A series may span at most " + props.maxSeriesWeeks()
                    + " weeks");
        }
        Template t = template(r.session());
        ZoneId zone = t.branch().zone();
        LocalDate firstDay = r.session().startsAt().atZone(zone).toLocalDate();
        LocalTime time = r.session().startsAt().atZone(zone).toLocalTime();
        Instant now = Instant.now(clock);
        UUID seriesId = UUID.randomUUID();
        List<SessionResponse> created = new ArrayList<>();
        List<SkippedSlot> skipped = new ArrayList<>();
        for (LocalDate day = firstDay; day.isBefore(firstDay.plusWeeks(r.weeks())); day = day.plusDays(1)) {
            if (!r.daysOfWeek().contains(day.getDayOfWeek().getValue())) {
                continue;
            }
            Instant start = day.atTime(time).atZone(zone).toInstant();
            Instant end = start.plus(t.duration());
            if (!start.isAfter(now)) {
                skipped.add(new SkippedSlot(start, "In the past"));
                continue;
            }
            String clash = clashWith(t.instructor().getId(), t.branch().getId(), t.room(), start, end, null);
            if (clash != null) {
                skipped.add(new SkippedSlot(start, clash));
                continue;
            }
            ClassSession session = sessionRepository.save(new ClassSession(t.type(), t.branch(), t.instructor(), t.room(),
                    start, end, t.policy(), seriesId));
            created.add(SessionResponse.from(session, Counts.EMPTY, null));
        }
        if (created.isEmpty()) {
            throw new BusinessRuleException("No classes were created: " + skipped.stream()
                    .map(s -> SLOT.format(s.startsAt().atZone(zone)) + " (" + s.reason() + ")").limit(5).toList());
        }
        sessionRepository.flush();
        // audit rows need an entity id: a series is recorded against its first class
        audit.record(AuditEvent.of("CLASS_SERIES", created.get(0).id(), "CREATED").branch(t.branch().getId())
                .with("seriesId", seriesId.toString()).with("classType", t.type().getName())
                .with("created", created.size()).with("skipped", skipped.size()));
        return new SeriesResponse(seriesId, created, skipped);
    }

    // ------------------------------------------------------------------ edit / cancel

    @Transactional
    public SessionResponse update(Long id, SessionUpdateRequest r) {
        ClassSession session = lock(id);
        classAccess.assertCanManage(session.getBranch().getId());
        Instant now = Instant.now(clock);
        Trainer instructor = instructorFor(r.instructorId(), session.getBranch());
        Instant end = r.startsAt().plus(Duration.ofMinutes(r.durationMinutes()));
        String room = blankToNull(r.room());
        String clash = clashWith(instructor.getId(), session.getBranch().getId(), room, r.startsAt(), end, id);
        if (clash != null) {
            throw new ConflictException(clash);
        }
        boolean timeChanged = !session.getStartsAt().equals(r.startsAt()) || !session.getEndsAt().equals(end);
        long seatsTaken = bookingRepository.countBySessionAndStatuses(id, BookingStatus.SEAT_HOLDING);
        session.reschedule(instructor, room, r.startsAt(), end, new Policy(r.capacity(), r.waitlistCapacity(),
                r.bookingOpensHours(), r.cancelCutoffMinutes()), seatsTaken, now);
        sessionRepository.flush();
        int promoted = promoter.fillFreedSeats(session, now);
        if (timeChanged) {
            List<Long> members = bookingRepository.findBySessionAndStatuses(id, BookingStatus.LIVE).stream()
                    .map(b -> b.getMember().getId()).toList();
            events.publishEvent(new ClassEvents.SessionRescheduled(id, members));
        }
        audit.record(AuditEvent.of("CLASS_SESSION", id, "UPDATED").branch(session.getBranch().getId())
                .with("startsAt", r.startsAt().toString()).with("capacity", r.capacity()).with("promoted", promoted));
        return view(session);
    }

    /** Cancels the class and releases every booking (no credit is used); booked and waitlisted members are notified. */
    @Transactional
    public SessionResponse cancel(Long id, String reason) {
        ClassSession session = lock(id);
        classAccess.assertCanManage(session.getBranch().getId());
        cancelLocked(session, reason, Instant.now(clock));
        return view(session);
    }

    @Transactional
    public SeriesCancelResponse cancelSeries(UUID seriesId, CancelSeriesRequest r) {
        List<ClassSession> series = sessionRepository.findBySeriesId(seriesId);
        if (series.isEmpty()) {
            throw new ResourceNotFoundException("Class series", seriesId);
        }
        GymBranch branch = series.get(0).getBranch();
        classAccess.assertCanManage(branch.getId());
        Instant now = Instant.now(clock);
        Instant from = r.from().atStartOfDay(branch.zone()).toInstant();
        int sessions = 0;
        int bookings = 0;
        for (Long sessionId : sessionRepository.findScheduledSeriesIdsFrom(seriesId, from.isAfter(now) ? from : now)) {
            ClassSession session = lock(sessionId);
            bookings += cancelLocked(session, r.reason(), now);
            sessions++;
        }
        audit.record(AuditEvent.of("CLASS_SERIES", series.get(0).getId(), "CANCELLED").branch(branch.getId())
                .with("seriesId", seriesId.toString()).with("from", r.from().toString()).with("sessions", sessions));
        return new SeriesCancelResponse(seriesId, sessions, bookings);
    }

    private int cancelLocked(ClassSession session, String reason, Instant now) {
        session.cancel(reason, now);
        List<ClassBooking> live = bookingRepository.findBySessionAndStatuses(session.getId(), BookingStatus.LIVE);
        live.forEach(b -> b.cancelWithoutPenalty(now));
        audit.record(AuditEvent.of("CLASS_SESSION", session.getId(), "CANCELLED").branch(session.getBranch().getId())
                .with("reason", reason).with("releasedBookings", live.size()));
        events.publishEvent(new ClassEvents.SessionCancelled(session.getId(),
                live.stream().map(b -> b.getMember().getId()).toList()));
        return live.size();
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public PageResponse<SessionResponse> search(Long branchId, LocalDate from, LocalDate to, Long classTypeId, Long instructorId,
                                                ClassSessionStatus status, boolean mine, Pageable pageable) {
        UserPrincipal caller = CurrentUser.get();
        ZoneId zone = clock.getZone();
        LocalDate start = from != null ? from : LocalDate.now(clock);
        LocalDate end = to != null ? to : start.plusDays(caller.isMemberOnly() ? 14 : 6);
        if (end.isBefore(start) || end.isAfter(start.plusDays(MAX_RANGE_DAYS))) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Date range must be 0-" + MAX_RANGE_DAYS + " days");
        }
        Instant fromI = start.atStartOfDay(zone).toInstant();
        Instant toI = end.plusDays(1).atStartOfDay(zone).toInstant();
        BranchScope scope;
        Long memberId = null;
        if (caller.isMemberOnly()) {
            memberId = caller.memberId();
            BranchScope visible = classAccess.memberVisibleBranches(memberId);
            if (branchId != null && !visible.includes(branchId)) {
                throw new ForbiddenException("Your membership does not include classes at branch " + branchId);
            }
            scope = branchId != null ? BranchScope.of(Set.of(branchId)) : visible;
            Instant now = Instant.now(clock);
            if (fromI.isBefore(now)) {
                fromI = now;   // members browse upcoming classes only
            }
        } else {
            scope = access.scope(branchId);
            if (mine) {
                if (caller.trainerId() == null) {
                    throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "mine=true requires a trainer login");
                }
                instructorId = caller.trainerId();
            }
        }
        Page<ClassSession> page = sessionRepository.search(scope.all(), scope.ids(), fromI, toI, classTypeId, instructorId,
                status, pageable);
        return PageResponse.of(page, withCountsMapper(page.getContent(), memberId));
    }

    @Transactional(readOnly = true)
    public SessionDetailResponse get(Long id) {
        ClassSession session = sessionRepository.findDetailed(id).orElseThrow(() -> new ResourceNotFoundException("Class", id));
        UserPrincipal caller = CurrentUser.get();
        Long branchId = session.getBranch().getId();
        if (caller.isMemberOnly()) {
            if (!classAccess.memberVisibleBranches(caller.memberId()).includes(branchId)) {
                throw new ForbiddenException("Your membership does not include classes at branch " + branchId);
            }
            SessionResponse view = withCountsMapper(List.of(session), caller.memberId()).apply(session);
            return new SessionDetailResponse(view, null, false, false, false);
        }
        if (!classAccess.canViewRoster(session)) {
            throw new ForbiddenException("You are not assigned to branch " + branchId);
        }
        List<ClassBooking> roster = bookingRepository.findRoster(id);
        List<RosterEntry> entries = new ArrayList<>();
        int position = 0;
        for (ClassBooking b : roster) {
            entries.add(RosterEntry.from(b, b.getStatus() == BookingStatus.WAITLISTED ? ++position : null));
        }
        return new SessionDetailResponse(view(session), entries, classAccess.canManage(branchId),
                classAccess.canMarkAttendance(session), classAccess.isDeskStaff(branchId));
    }

    SessionResponse view(ClassSession session) {
        return withCountsMapper(List.of(session), null).apply(session);
    }

    /** Builds a mapper with booking counts (and the member's own booking) loaded in two queries for the whole page. */
    private Function<ClassSession, SessionResponse> withCountsMapper(Collection<ClassSession> sessions, Long memberId) {
        List<Long> ids = sessions.stream().map(ClassSession::getId).toList();
        Map<Long, Map<BookingStatus, Long>> counts = new HashMap<>();
        Map<Long, ClassBooking> mine = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : bookingRepository.countsBySession(ids)) {
                counts.computeIfAbsent((Long) row[0], k -> new EnumMap<>(BookingStatus.class))
                        .put((BookingStatus) row[1], ((Number) row[2]).longValue());
            }
            if (memberId != null) {
                for (ClassBooking b : bookingRepository.findMemberBookingsForSessions(memberId, ids)) {
                    mine.putIfAbsent(b.getSession().getId(), b);   // newest first
                }
            }
        }
        return s -> {
            Map<BookingStatus, Long> c = counts.getOrDefault(s.getId(), Map.of());
            long booked = c.getOrDefault(BookingStatus.BOOKED, 0L) + c.getOrDefault(BookingStatus.ATTENDED, 0L)
                    + c.getOrDefault(BookingStatus.NO_SHOW, 0L);
            return SessionResponse.from(s, new Counts(booked, c.getOrDefault(BookingStatus.WAITLISTED, 0L),
                    c.getOrDefault(BookingStatus.ATTENDED, 0L), c.getOrDefault(BookingStatus.NO_SHOW, 0L),
                    c.getOrDefault(BookingStatus.LATE_CANCELLED, 0L)), mine.get(s.getId()));
        };
    }

    // ------------------------------------------------------------------ helpers

    private record Template(ClassType type, GymBranch branch, Trainer instructor, String room, Duration duration,
                            Policy policy) {
    }

    private Template template(SessionRequest r) {
        classAccess.assertCanManage(r.branchId());
        ClassType type = typeService.load(r.classTypeId());
        if (!type.isActive()) {
            throw new BusinessRuleException("Class type '" + type.getName() + "' is inactive");
        }
        GymBranch branch = branchService.load(r.branchId());
        Trainer instructor = instructorFor(r.instructorId(), branch);
        int duration = r.durationMinutes() != null ? r.durationMinutes() : type.getDefaultDurationMin();
        Policy policy = new Policy(
                r.capacity() != null ? r.capacity() : type.getDefaultCapacity(),
                r.waitlistCapacity() != null ? r.waitlistCapacity() : DEFAULT_WAITLIST,
                r.bookingOpensHours() != null ? r.bookingOpensHours() : DEFAULT_BOOKING_OPENS_HOURS,
                r.cancelCutoffMinutes() != null ? r.cancelCutoffMinutes() : DEFAULT_CANCEL_CUTOFF_MINUTES);
        return new Template(type, branch, instructor, blankToNull(r.room()), Duration.ofMinutes(duration), policy);
    }

    /** An active trainer whose home branch is, or who is assigned to, the class's branch. */
    private Trainer instructorFor(Long trainerId, GymBranch branch) {
        Trainer trainer = trainerService.load(trainerId);
        if (!trainer.isActive()) {
            throw new BusinessRuleException("Instructor " + trainer.getUser().getFullName() + " is " + trainer.getStatus());
        }
        boolean atBranch = Objects.equals(trainer.getHomeBranch().getId(), branch.getId())
                || staffAssignments.findUserIdsByBranchId(branch.getId()).contains(trainer.getUser().getId());
        if (!atBranch) {
            throw new BusinessRuleException("Instructor " + trainer.getUser().getFullName() + " does not work at branch "
                    + branch.getCode());
        }
        return trainer;
    }

    /** Human-readable clash description, or null if the slot is free for the instructor and room. */
    private String clashWith(Long instructorId, Long branchId, String room, Instant start, Instant end, Long excludeId) {
        List<ClassSession> instructorClash = sessionRepository.findInstructorOverlaps(instructorId, start, end, excludeId);
        if (!instructorClash.isEmpty()) {
            ClassSession other = instructorClash.get(0);
            return "The instructor already teaches " + other.getClassType().getName() + " at "
                    + SLOT.format(other.getStartsAt().atZone(clock.getZone()));
        }
        if (room != null) {
            List<ClassSession> roomClash = sessionRepository.findRoomOverlaps(branchId, room, start, end, excludeId);
            if (!roomClash.isEmpty()) {
                return "Room " + room + " is taken by " + roomClash.get(0).getClassType().getName() + " at "
                        + SLOT.format(roomClash.get(0).getStartsAt().atZone(clock.getZone()));
            }
        }
        return null;
    }

    private ClassSession lock(Long id) {
        return sessionRepository.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Class", id));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
