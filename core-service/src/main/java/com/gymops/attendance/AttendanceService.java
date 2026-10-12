package com.gymops.attendance;

import com.gymops.attendance.dto.AttendanceDtos.AttendanceResponse;
import com.gymops.attendance.dto.AttendanceDtos.CheckInRequest;
import com.gymops.attendance.dto.AttendanceDtos.DailySummary;
import com.gymops.attendance.dto.AttendanceDtos.Occupancy;
import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.branch.BranchService;
import com.gymops.branch.GymBranch;
import com.gymops.common.api.PageResponse;
import com.gymops.common.event.DomainEvents;
import com.gymops.common.exception.ApiException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.config.AppProperties;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.member.MemberStatus;
import com.gymops.member.MembershipState;
import com.gymops.plan.MembershipPlan;
import com.gymops.security.AccessPolicy;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import com.gymops.subscription.MembershipQueryService;
import com.gymops.subscription.Subscription;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Check-in / check-out. Duplicate check-ins are prevented in two layers: the member row is locked
 * for the duration of the check-in (concurrent taps at two turnstiles serialise), and the partial
 * unique index {@code ux_attendance_one_open_visit} rejects a second open visit regardless.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceService {

    private final AttendanceRepository repository;
    private final MemberRepository memberRepository;
    private final BranchService branchService;
    private final MembershipQueryService membershipQuery;
    private final AccessPolicy access;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meterRegistry;
    private final AppProperties props;
    private final Clock clock;

    @Transactional
    public AttendanceResponse checkIn(CheckInRequest request) {
        UserPrincipal caller = CurrentUser.get();
        Long memberId = request.memberId() != null ? request.memberId()
                : memberRepository.findByMemberCode(request.memberCode().trim().toUpperCase())
                        .map(Member::getId)
                        .orElseThrow(() -> new ResourceNotFoundException("Member", request.memberCode()));
        CheckInMethod method = request.method() != null ? request.method()
                : caller.isMemberOnly() ? CheckInMethod.MOBILE_APP : CheckInMethod.FRONT_DESK;
        if (caller.isMemberOnly()) {
            if (!Objects.equals(caller.memberId(), memberId)) {
                throw new ForbiddenException("Members can only check themselves in");
            }
            if (method == CheckInMethod.FRONT_DESK) {
                throw new ForbiddenException("FRONT_DESK check-ins are performed by staff");
            }
        } else {
            access.assertBranchAccess(request.branchId());
        }

        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        GymBranch branch = branchService.load(request.branchId());
        Instant now = Instant.now(clock);

        EntryCheck check = evaluateEntry(member, branch, now, EntryCheck.Options.DESK);
        if (!check.isAllowed()) {
            meterRegistry.counter("gymops.checkins.denied", "reason", check.denial().metricTag()).increment();
            if (check.denial() == EntryDenial.ALREADY_INSIDE) {
                throw new ConflictException(ErrorCode.DUPLICATE_CHECK_IN, check.message());
            }
            throw new ApiException(check.denial().errorCode(), check.message());
        }
        return AttendanceResponse.from(recordEntry(member, branch, check.subscription(), now, method, caller.userId()));
    }

    /**
     * The entry rules shared by front-desk check-in and gate devices: member account status, an already
     * open visit, opening hours, an access-granting subscription (ACTIVE / GRACE covering the day), plan
     * rules (home branch only, daily access window, monthly visit cap) and live capacity.
     *
     * <p>The caller must hold the member row lock ({@link MemberRepository#findByIdForUpdate}) so that the
     * decision and the visit it creates are atomic per member. {@code at} is the instant the member is at the
     * door; for late (offline) gate events it lies in the past.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EntryCheck evaluateEntry(Member member, GymBranch branch, Instant at, EntryCheck.Options options) {
        Long memberId = member.getId();
        if (!member.isActive()) {
            EntryDenial denial = member.getStatus() == MemberStatus.SUSPENDED ? EntryDenial.MEMBER_SUSPENDED
                    : EntryDenial.MEMBER_INACTIVE;
            return EntryCheck.denied(denial, "Member account is " + member.getStatus());
        }
        if (options.checkOpenVisit()) {
            Optional<Attendance> open = repository.findOpenVisit(memberId);
            if (open.isPresent()) {
                return EntryCheck.denied(EntryDenial.ALREADY_INSIDE, "Member is already checked in (visit "
                        + open.get().getId() + " at branch " + open.get().getBranch().getId() + " since "
                        + open.get().getCheckInTime() + ")");
            }
        }
        if (!branch.isOpenAt(at)) {
            return EntryCheck.denied(EntryDenial.BRANCH_CLOSED, "Branch " + branch.getCode() + " is closed now");
        }
        LocalDate day = at.atZone(clock.getZone()).toLocalDate();
        Optional<Subscription> granting = membershipQuery.accessGrantingSubscription(memberId, day);
        if (granting.isEmpty()) {
            MembershipState state = membershipQuery.currentMembership(memberId).state();
            return EntryCheck.denied(state == MembershipState.FROZEN ? EntryDenial.MEMBERSHIP_FROZEN
                    : EntryDenial.NO_ACTIVE_MEMBERSHIP, "No active membership (current state: " + state + ")");
        }
        Subscription subscription = granting.get();
        EntryCheck planCheck = checkPlanRules(subscription, branch, memberId, at);
        if (planCheck != null) {
            return planCheck;
        }
        if (options.checkCapacity()) {
            long inside = repository.countOpenAtBranch(branch.getId());
            if (inside >= branch.getCapacity()) {
                return EntryCheck.denied(EntryDenial.AT_CAPACITY, "Branch is at capacity (" + branch.getCapacity() + ")");
            }
        }
        return EntryCheck.allowed(subscription);
    }

    /**
     * Inserts the visit for an admitted member (caller holds the member row lock and has evaluated the rules).
     * {@code checkedInBy} is null for gate devices (system actor). The partial unique index on open visits
     * remains the final guard against a second open visit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Attendance recordEntry(Member member, GymBranch branch, Subscription subscription, Instant at,
                                  CheckInMethod method, Long checkedInBy) {
        Attendance visit = repository.saveAndFlush(new Attendance(member, branch, subscription, at, method, checkedInBy));
        long totalVisits = repository.countByMemberId(member.getId());
        if (totalVisits % props.attendance().milestoneEvery() == 0) {
            events.publishEvent(new DomainEvents.AttendanceMilestone(member.getId(), totalVisits));
        }
        meterRegistry.counter("gymops.checkins", "branch", branch.getCode(), "method", method.name()).increment();
        return visit;
    }

    /** The member's open visit, row-locked (take the member row lock first: member → visit lock order). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Attendance> lockOpenVisit(Long memberId) {
        return repository.findOpenVisitForUpdate(memberId);
    }

    /** Closes a visit at {@code at} (gate exit; also used when a gate reconciles an offline exit). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Attendance recordExit(Attendance visit, Instant at) {
        visit.checkOut(at);
        // flush now: a new visit inserted right after (re-entry) must not collide with this one on the
        // "one open visit per member" index, and Hibernate would otherwise flush the insert first
        return repository.saveAndFlush(visit);
    }

    @Transactional
    public AttendanceResponse checkOut(Long memberId) {
        UserPrincipal caller = CurrentUser.get();
        Attendance visit = repository.findOpenVisitForUpdate(memberId)
                .orElseThrow(() -> new ConflictException(ErrorCode.INVALID_STATE_TRANSITION,
                        "Member " + memberId + " is not checked in"));
        if (caller.isMemberOnly()) {
            if (!Objects.equals(caller.memberId(), memberId)) {
                throw new ForbiddenException("Members can only check themselves out");
            }
        } else {
            access.assertBranchAccess(visit.getBranch().getId());
        }
        visit.checkOut(Instant.now(clock));
        return AttendanceResponse.from(visit);
    }

    /** Closes visits that were never checked out (forgotten tap-out); runs from a scheduled job. */
    @Transactional
    public boolean autoCheckOut(Long attendanceId) {
        Attendance visit = repository.findById(attendanceId).orElse(null);
        if (visit == null || !visit.isOpen()) {
            return false;
        }
        visit.autoCheckOut(props.attendance().assumedVisitDuration());
        audit.record(AuditEvent.of("ATTENDANCE", attendanceId, "AUTO_CHECKED_OUT").member(visit.getMember().getId())
                .branch(visit.getBranch().getId()));
        events.publishEvent(new DomainEvents.VisitAutoClosed(attendanceId, visit.getMember().getId()));
        return true;
    }

    @Transactional(readOnly = true)
    public List<Long> openVisitIdsBefore(Long branchId, Instant cutoff) {
        return repository.findOpenIdsAtBranchBefore(branchId, cutoff);
    }

    /**
     * Ends a visit at a free-exit (push-button) branch, where nobody scans out: checked out {@code assumed} after
     * entry. Unlike {@link #autoCheckOut} this is the normal case there, so the member is not notified.
     */
    @Transactional
    public boolean closeAssumedVisit(Long attendanceId, Duration assumed) {
        Attendance visit = repository.findById(attendanceId).orElse(null);
        if (visit == null || !visit.isOpen()) {
            return false;
        }
        visit.autoCheckOut(assumed);
        return true;
    }

    @Transactional(readOnly = true)
    public PageResponse<AttendanceResponse> memberHistory(Long memberId, LocalDate from, LocalDate to, Pageable pageable) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        access.assertMemberAccess(memberId, member.getHomeBranch().getId());
        ZoneId zone = clock.getZone();
        LocalDate start = from != null ? from : LocalDate.now(clock).minusDays(90);
        LocalDate end = to != null ? to : LocalDate.now(clock);
        return PageResponse.of(repository.findMemberHistory(memberId, start.atStartOfDay(zone).toInstant(),
                end.plusDays(1).atStartOfDay(zone).toInstant(), pageable), AttendanceResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<AttendanceResponse> branchVisits(Long branchId, LocalDate date, Pageable pageable) {
        access.assertBranchAccess(branchId);
        GymBranch branch = branchService.load(branchId);
        LocalDate day = date != null ? date : LocalDate.now(branch.zone());
        return PageResponse.of(repository.findBranchVisits(branchId, day.atStartOfDay(branch.zone()).toInstant(),
                day.plusDays(1).atStartOfDay(branch.zone()).toInstant(), pageable), AttendanceResponse::from);
    }

    @Transactional(readOnly = true)
    public DailySummary dailySummary(Long branchId, LocalDate date) {
        access.assertBranchAccess(branchId);
        GymBranch branch = branchService.load(branchId);
        LocalDate day = date != null ? date : LocalDate.now(branch.zone());
        Instant from = day.atStartOfDay(branch.zone()).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(branch.zone()).toInstant();
        List<Object[]> counts = repository.visitAndUniqueCounts(branchId, from, to);
        long visits = counts.isEmpty() ? 0 : ((Number) counts.get(0)[0]).longValue();
        long unique = counts.isEmpty() ? 0 : ((Number) counts.get(0)[1]).longValue();
        Double avg = repository.averageVisitMinutes(branchId, from, to);
        return new DailySummary(branchId, day, visits, unique, repository.countOpenAtBranch(branchId),
                branch.getCapacity(), avg == null ? 0 : Math.round(avg * 10) / 10.0);
    }

    @Transactional(readOnly = true)
    public Occupancy occupancy(Long branchId) {
        access.assertBranchAccess(branchId);
        GymBranch branch = branchService.load(branchId);
        long inside = repository.countOpenAtBranch(branchId);
        return new Occupancy(branchId, inside, branch.getCapacity(),
                Math.round(inside * 1000.0 / branch.getCapacity()) / 1000.0);
    }

    private EntryCheck checkPlanRules(Subscription subscription, GymBranch branch, Long memberId, Instant at) {
        MembershipPlan plan = subscription.getPlan();
        if (!plan.isAllBranchAccess() && !subscription.getBranch().getId().equals(branch.getId())) {
            return EntryCheck.denied(EntryDenial.BRANCH_NOT_INCLUDED,
                    "Plan " + plan.getCode() + " is valid only at the member's home branch");
        }
        ZonedDateTime local = at.atZone(branch.zone());
        if (!plan.isAccessibleAt(local.toLocalTime())) {
            return EntryCheck.denied(EntryDenial.OUTSIDE_PLAN_HOURS, "Plan " + plan.getCode()
                    + " allows entry only between " + plan.getAccessStartTime() + " and " + plan.getAccessEndTime());
        }
        if (plan.getMaxEntriesPerDay() != null) {
            ZonedDateTime dayStart = local.toLocalDate().atStartOfDay(branch.zone());
            long today = repository.countMemberVisitsBetween(memberId, dayStart.toInstant(), dayStart.plusDays(1).toInstant());
            if (today >= plan.getMaxEntriesPerDay()) {
                return EntryCheck.denied(EntryDenial.DAILY_ENTRY_LIMIT, "Daily entry limit of " + plan.getMaxEntriesPerDay()
                        + (plan.getMaxEntriesPerDay() == 1 ? " entry" : " entries") + " reached for today");
            }
        }
        if (plan.getMaxVisitsPerMonth() != null) {
            ZonedDateTime monthStart = local.toLocalDate().withDayOfMonth(1).atStartOfDay(branch.zone());
            long visits = repository.countMemberVisitsBetween(memberId, monthStart.toInstant(),
                    monthStart.plusMonths(1).toInstant());
            if (visits >= plan.getMaxVisitsPerMonth()) {
                return EntryCheck.denied(EntryDenial.VISIT_LIMIT,
                        "Monthly visit limit of " + plan.getMaxVisitsPerMonth() + " reached");
            }
        }
        return null;
    }
}
