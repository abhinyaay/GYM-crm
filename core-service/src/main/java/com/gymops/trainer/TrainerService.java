package com.gymops.trainer;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.branch.BranchService;
import com.gymops.branch.GymBranch;
import com.gymops.common.api.PageResponse;
import com.gymops.common.event.DomainEvents;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.member.Member;
import com.gymops.member.MemberService;
import com.gymops.security.AccessPolicy;
import com.gymops.security.BranchScope;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import com.gymops.trainer.dto.TrainerDtos.AssignMemberRequest;
import com.gymops.trainer.dto.TrainerDtos.AssignmentResponse;
import com.gymops.trainer.dto.TrainerDtos.AvailabilityRequest;
import com.gymops.trainer.dto.TrainerDtos.PerformanceResponse;
import com.gymops.trainer.dto.TrainerDtos.SessionRequest;
import com.gymops.trainer.dto.TrainerDtos.SessionResponse;
import com.gymops.trainer.dto.TrainerDtos.SessionStatusRequest;
import com.gymops.trainer.dto.TrainerDtos.TrainerRequest;
import com.gymops.trainer.dto.TrainerDtos.TrainerResponse;
import com.gymops.trainer.dto.TrainerDtos.TrainerUpdateRequest;
import com.gymops.user.RoleName;
import com.gymops.user.User;
import com.gymops.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TrainerService {

    private final TrainerRepository trainerRepository;
    private final MemberTrainerRepository assignmentRepository;
    private final TrainingSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final BranchService branchService;
    private final MemberService memberService;
    private final AccessPolicy access;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ------------------------------------------------------------------ profiles

    @Transactional
    public TrainerResponse create(TrainerRequest r) {
        User user = userRepository.findById(r.userId()).orElseThrow(() -> new ResourceNotFoundException("User", r.userId()));
        if (!user.hasRole(RoleName.TRAINER)) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "User " + r.userId() + " does not have the TRAINER role");
        }
        if (trainerRepository.findIdByUserId(user.getId()).isPresent()) {
            throw new ConflictException("User already has a trainer profile");
        }
        if (trainerRepository.existsByEmployeeCodeIgnoreCase(r.employeeCode())) {
            throw new ConflictException("Employee code " + r.employeeCode() + " is already in use");
        }
        access.assertBranchAccess(r.homeBranchId());
        Trainer trainer = new Trainer(user, branchService.load(r.homeBranchId()), r.employeeCode());
        trainer.setBio(r.bio());
        trainer.setCertifications(r.certifications());
        trainer.setYearsOfExperience(r.yearsOfExperience());
        trainer.setHourlyRate(r.hourlyRate());
        if (r.maxActiveClients() != null) {
            trainer.setMaxActiveClients(r.maxActiveClients());
        }
        trainer.replaceSpecializations(r.specializations());
        trainerRepository.save(trainer);
        audit.record(AuditEvent.of("TRAINER", trainer.getId(), "CREATED").branch(r.homeBranchId()));
        return TrainerResponse.from(trainer);
    }

    @Transactional
    public TrainerResponse update(Long id, TrainerUpdateRequest r) {
        Trainer trainer = load(id);
        assertSelfOrManager(trainer);
        UserPrincipal caller = CurrentUser.get();
        boolean self = Objects.equals(caller.trainerId(), id);
        if (self && !caller.hasAnyRole(List.of(RoleName.MANAGER, RoleName.GYM_ADMIN, RoleName.SUPER_ADMIN))) {
            // trainers may edit their own bio/specialisations, not commercial or status fields
            if (!Objects.equals(trainer.getHomeBranch().getId(), r.homeBranchId()) || r.status() != null
                    || !sameAmount(trainer.getHourlyRate(), r.hourlyRate())) {
                throw new ForbiddenException("Trainers cannot change branch, status or rate");
            }
        } else if (!Objects.equals(trainer.getHomeBranch().getId(), r.homeBranchId())) {
            access.assertBranchAccess(r.homeBranchId());
            trainer.setHomeBranch(branchService.load(r.homeBranchId()));
        }
        trainer.setBio(r.bio());
        trainer.setCertifications(r.certifications());
        trainer.setYearsOfExperience(r.yearsOfExperience());
        trainer.setHourlyRate(r.hourlyRate());
        if (r.maxActiveClients() != null) {
            trainer.setMaxActiveClients(r.maxActiveClients());
        }
        if (r.status() != null) {
            trainer.setStatus(r.status());
        }
        trainer.replaceSpecializations(r.specializations());
        audit.record(AuditEvent.of("TRAINER", id, "UPDATED"));
        return TrainerResponse.from(trainer);
    }

    @Transactional(readOnly = true)
    public TrainerResponse get(Long id) {
        return TrainerResponse.from(load(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<TrainerResponse> search(Long branchId, TrainerStatus status, String specialization, Pageable pageable) {
        UserPrincipal caller = CurrentUser.get();
        // members browse trainers of every branch; staff are scoped to their branches
        BranchScope scope = caller.isMemberOnly() ? BranchScope.ALL : access.scope(branchId);
        if (caller.isMemberOnly() && branchId != null) {
            scope = BranchScope.of(java.util.Set.of(branchId));
        }
        String spec = specialization == null ? null : specialization.trim().toUpperCase();
        return PageResponse.of(trainerRepository.search(scope.all(), scope.ids(), status, spec, pageable), TrainerResponse::from);
    }

    @Transactional
    public TrainerResponse replaceAvailability(Long id, AvailabilityRequest r) {
        Trainer trainer = load(id);
        assertSelfOrManager(trainer);
        List<TrainerAvailability> slots = r.slots().stream().map(s -> {
            GymBranch branch = branchService.load(s.branchId());
            return new TrainerAvailability(branch, s.dayOfWeek(), s.startTime(), s.endTime());
        }).toList();
        for (int i = 0; i < slots.size(); i++) {
            for (int j = i + 1; j < slots.size(); j++) {
                if (slots.get(i).overlaps(slots.get(j))) {
                    throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Availability slots overlap on day "
                            + slots.get(i).getDayOfWeek());
                }
            }
        }
        trainer.replaceAvailability(slots);
        audit.record(AuditEvent.of("TRAINER", id, "AVAILABILITY_CHANGED").with("slots", slots.size()));
        return TrainerResponse.from(trainer);
    }

    // ------------------------------------------------------------------ member assignment

    @Transactional
    public AssignmentResponse assignMember(Long trainerId, AssignMemberRequest r) {
        Trainer trainer = load(trainerId);
        Member member = memberService.load(r.memberId());
        access.assertBranchAccess(member.getHomeBranch().getId());
        if (!trainer.isActive()) {
            throw new BusinessRuleException("Trainer is " + trainer.getStatus());
        }
        if (!trainer.getHomeBranch().getId().equals(member.getHomeBranch().getId())) {
            throw new BusinessRuleException("Trainer and member must belong to the same branch");
        }
        if (assignmentRepository.countActiveByTrainerId(trainerId) >= trainer.getMaxActiveClients()) {
            throw new BusinessRuleException("Trainer has reached the maximum of " + trainer.getMaxActiveClients() + " clients");
        }
        Instant now = Instant.now(clock);
        // re-assignment ends the previous trainer relationship in the same transaction
        assignmentRepository.findActiveByMemberId(member.getId()).ifPresent(existing -> {
            if (existing.getTrainer().getId().equals(trainerId)) {
                throw new ConflictException("Member is already assigned to this trainer");
            }
            existing.end(now);
            assignmentRepository.saveAndFlush(existing);
        });
        MemberTrainer assignment = assignmentRepository.saveAndFlush(
                new MemberTrainer(member, trainer, r.goal(), now, CurrentUser.idOrNull()));
        audit.record(AuditEvent.of("MEMBER", member.getId(), "TRAINER_ASSIGNED").member(member.getId())
                .with("trainerId", trainerId));
        events.publishEvent(new DomainEvents.TrainerAssigned(member.getId(), trainerId));
        return AssignmentResponse.from(assignment);
    }

    @Transactional
    public void endAssignment(Long trainerId, Long memberId) {
        Trainer trainer = load(trainerId);
        assertSelfOrManager(trainer);
        MemberTrainer assignment = assignmentRepository.findActiveByMemberId(memberId)
                .filter(a -> a.getTrainer().getId().equals(trainerId))
                .orElseThrow(() -> new ResourceNotFoundException("Active assignment for member", memberId));
        assignment.end(Instant.now(clock));
        audit.record(AuditEvent.of("MEMBER", memberId, "TRAINER_UNASSIGNED").member(memberId).with("trainerId", trainerId));
    }

    @Transactional(readOnly = true)
    public List<AssignmentResponse> clients(Long trainerId) {
        Trainer trainer = load(trainerId);
        assertSelfOrBranchStaff(trainer);
        return assignmentRepository.findActiveByTrainerId(trainerId).stream().map(AssignmentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Optional<AssignmentResponse> trainerOfMember(Long memberId) {
        return assignmentRepository.findActiveByMemberId(memberId).map(AssignmentResponse::from);
    }

    // ------------------------------------------------------------------ sessions

    @Transactional
    public SessionResponse scheduleSession(Long trainerId, SessionRequest r) {
        Trainer trainer = load(trainerId);
        assertSelfOrManager(trainer);
        if (!trainer.isActive()) {
            throw new BusinessRuleException("Trainer is " + trainer.getStatus());
        }
        Member member = memberService.load(r.memberId());
        if (!assignmentRepository.isActiveClient(trainerId, member.getId())) {
            throw new BusinessRuleException("Member " + member.getMemberCode() + " is not an active client of this trainer");
        }
        Instant start = r.startsAt();
        Instant end = start.plusSeconds(r.durationMinutes() * 60L);
        TrainerAvailability slot = trainer.getAvailability().stream()
                .filter(a -> {
                    ZonedDateTime s = start.atZone(a.getBranch().zone());
                    ZonedDateTime e = end.atZone(a.getBranch().zone());
                    return s.toLocalDate().equals(e.toLocalDate())
                            && a.covers(a.getBranch().getId(), s.getDayOfWeek().getValue(), s.toLocalTime(), e.toLocalTime());
                })
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("Requested time is outside the trainer's availability"));
        // overlapping bookings for trainer or member are rejected by exclusion constraints
        TrainingSession session = sessionRepository.saveAndFlush(
                new TrainingSession(trainer, member, slot.getBranch(), start, end, r.notes()));
        events.publishEvent(new DomainEvents.SessionScheduled(session.getId(), member.getId(), trainerId));
        audit.record(AuditEvent.of("TRAINING_SESSION", session.getId(), "SCHEDULED").member(member.getId())
                .with("trainerId", trainerId).with("startsAt", start.toString()));
        return SessionResponse.from(session);
    }

    @Transactional
    public SessionResponse updateSessionStatus(Long trainerId, Long sessionId, SessionStatusRequest r) {
        Trainer trainer = load(trainerId);
        assertSelfOrManager(trainer);
        TrainingSession session = sessionRepository.findById(sessionId)
                .filter(s -> s.getTrainer().getId().equals(trainerId))
                .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId));
        session.transitionTo(r.status(), r.notes(), Instant.now(clock));
        audit.record(AuditEvent.of("TRAINING_SESSION", sessionId, r.status().name()).member(session.getMember().getId()));
        return SessionResponse.from(session);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> schedule(Long trainerId, LocalDate from, LocalDate to) {
        Trainer trainer = load(trainerId);
        assertSelfOrBranchStaff(trainer);
        LocalDate start = from != null ? from : LocalDate.now(clock);
        LocalDate end = to != null ? to : start.plusDays(7);
        if (end.isBefore(start) || end.isAfter(start.plusDays(92))) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Date range must be 0-92 days");
        }
        return sessionRepository.findTrainerSchedule(trainerId, start.atStartOfDay(clock.getZone()).toInstant(),
                end.plusDays(1).atStartOfDay(clock.getZone()).toInstant()).stream().map(SessionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public PerformanceResponse performance(Long trainerId, LocalDate from, LocalDate to) {
        Trainer trainer = load(trainerId);
        assertSelfOrBranchStaff(trainer);
        LocalDate start = from != null ? from : LocalDate.now(clock).minusDays(30);
        LocalDate end = to != null ? to : LocalDate.now(clock);
        Instant fromI = start.atStartOfDay(clock.getZone()).toInstant();
        Instant toI = end.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        Map<SessionStatus, Long> byStatus = new EnumMap<>(SessionStatus.class);
        for (SessionStatus s : SessionStatus.values()) {
            byStatus.put(s, 0L);
        }
        for (Object[] row : sessionRepository.countByStatus(trainerId, fromI, toI)) {
            byStatus.put((SessionStatus) row[0], ((Number) row[1]).longValue());
        }
        long completed = byStatus.get(SessionStatus.COMPLETED);
        long noShow = byStatus.get(SessionStatus.NO_SHOW);
        long concluded = completed + noShow + byStatus.get(SessionStatus.CANCELLED);
        return new PerformanceResponse(trainerId, fromI, toI, assignmentRepository.countActiveByTrainerId(trainerId),
                byStatus, ratio(completed, concluded), ratio(noShow, completed + noShow));
    }

    public Trainer load(Long id) {
        return trainerRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Trainer", id));
    }

    /** BigDecimal.equals compares scale (1000.0 != 1000.00); money must be compared by value. */
    private static boolean sameAmount(java.math.BigDecimal a, java.math.BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static double ratio(long a, long b) {
        return b == 0 ? 0 : Math.round(a * 10000.0 / b) / 10000.0;
    }

    /** The trainer themselves, or a manager/admin with access to the trainer's branch. */
    private void assertSelfOrManager(Trainer trainer) {
        UserPrincipal caller = CurrentUser.get();
        if (Objects.equals(caller.trainerId(), trainer.getId())) {
            return;
        }
        if (caller.hasAnyRole(List.of(RoleName.SUPER_ADMIN, RoleName.GYM_ADMIN, RoleName.MANAGER))
                && access.canAccessBranch(trainer.getHomeBranch().getId())) {
            return;
        }
        throw new ForbiddenException("Not allowed to manage trainer " + trainer.getId());
    }

    private void assertSelfOrBranchStaff(Trainer trainer) {
        UserPrincipal caller = CurrentUser.get();
        if (Objects.equals(caller.trainerId(), trainer.getId())) {
            return;
        }
        if (caller.isStaff() && !caller.hasRole(RoleName.TRAINER) || caller.isOrgWide()) {
            access.assertBranchAccess(trainer.getHomeBranch().getId());
            return;
        }
        throw new ForbiddenException("Not allowed to view trainer " + trainer.getId() + " data");
    }
}
