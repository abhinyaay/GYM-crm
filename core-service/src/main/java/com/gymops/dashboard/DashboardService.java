package com.gymops.dashboard;

import com.gymops.attendance.AttendanceRepository;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.dashboard.dto.AdminDashboardResponse;
import com.gymops.dashboard.dto.MemberDashboardResponse;
import com.gymops.dashboard.dto.TrainerDashboardResponse;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.notification.NotificationRepository;
import com.gymops.payment.PaymentRepository;
import com.gymops.payment.dto.PaymentResponse;
import com.gymops.security.AccessPolicy;
import com.gymops.security.BranchScope;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import com.gymops.subscription.MembershipQueryService;
import com.gymops.subscription.dto.MembershipSummary;
import com.gymops.trainer.MemberTrainer;
import com.gymops.trainer.MemberTrainerRepository;
import com.gymops.trainer.Trainer;
import com.gymops.trainer.TrainerRepository;
import com.gymops.trainer.TrainingSessionRepository;
import com.gymops.trainer.dto.TrainerDtos.AssignmentResponse;
import com.gymops.trainer.dto.TrainerDtos.SessionResponse;
import com.gymops.workout.MemberProgress;
import com.gymops.workout.MemberProgressRepository;
import com.gymops.workout.WorkoutPlan;
import com.gymops.workout.WorkoutPlanRepository;
import com.gymops.workout.dto.WorkoutDtos.WorkoutPlanResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final AdminDashboardCalculator adminCalculator;
    private final AccessPolicy access;
    private final TrainerRepository trainerRepository;
    private final MemberTrainerRepository assignmentRepository;
    private final TrainingSessionRepository sessionRepository;
    private final WorkoutPlanRepository workoutPlanRepository;
    private final MemberProgressRepository progressRepository;
    private final AttendanceRepository attendanceRepository;
    private final MemberRepository memberRepository;
    private final PaymentRepository paymentRepository;
    private final NotificationRepository notificationRepository;
    private final MembershipQueryService membershipQuery;
    private final Clock clock;

    public AdminDashboardResponse admin(Long branchId) {
        BranchScope scope = access.scope(branchId);
        return adminCalculator.compute(scope, branchId, currentBearerToken());
    }

    @Transactional(readOnly = true)
    public TrainerDashboardResponse trainer(Long requestedTrainerId) {
        UserPrincipal caller = CurrentUser.get();
        Long trainerId = requestedTrainerId != null ? requestedTrainerId : caller.trainerId();
        if (trainerId == null) {
            throw new BusinessRuleException("No trainer profile linked to this account; pass trainerId");
        }
        Trainer trainer = trainerRepository.findById(trainerId).orElseThrow(() -> new ResourceNotFoundException("Trainer", trainerId));
        if (!trainerId.equals(caller.trainerId())) {
            access.assertBranchAccess(trainer.getHomeBranch().getId());
        }
        ZoneId zone = trainer.getHomeBranch().zone();
        LocalDate today = LocalDate.now(zone);
        List<SessionResponse> sessions = sessionRepository.findTrainerSchedule(trainerId,
                today.atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant())
                .stream().map(SessionResponse::from).toList();
        List<MemberTrainer> clients = assignmentRepository.findActiveByTrainerId(trainerId);
        List<WorkoutPlan> plans = workoutPlanRepository.findActiveMemberPlansByTrainer(trainerId);
        List<TrainerDashboardResponse.ClientProgress> progress = clients.stream().map(a -> {
            Member m = a.getMember();
            Optional<MemberProgress> first = progressRepository.findFirstByMemberIdOrderByRecordedAtAsc(m.getId());
            Optional<MemberProgress> latest = progressRepository.findFirstByMemberIdOrderByRecordedAtDesc(m.getId());
            BigDecimal start = first.map(MemberProgress::getWeightKg).orElse(null);
            BigDecimal now = latest.map(MemberProgress::getWeightKg).orElse(null);
            String plan = plans.stream().filter(p -> p.getMember().getId().equals(m.getId()))
                    .map(WorkoutPlan::getName).findFirst().orElse(null);
            LocalDate lastVisit = attendanceRepository.findLastVisit(m.getId()).map(i -> i.atZone(zone).toLocalDate()).orElse(null);
            return new TrainerDashboardResponse.ClientProgress(m.getId(), m.getMemberCode(), m.fullName(), plan, lastVisit,
                    start, now, start != null && now != null ? now.subtract(start) : null,
                    latest.map(MemberProgress::getRecordedAt).orElse(null));
        }).toList();
        return new TrainerDashboardResponse(trainerId, trainer.getUser().getFullName(), clients.size(),
                trainer.getMaxActiveClients(), sessions, plans.size(), progress);
    }

    @Transactional(readOnly = true)
    public MemberDashboardResponse member(Long requestedMemberId) {
        UserPrincipal caller = CurrentUser.get();
        Long memberId = requestedMemberId != null ? requestedMemberId : caller.memberId();
        if (memberId == null) {
            throw new BusinessRuleException("No member profile linked to this account; pass memberId");
        }
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        access.assertMemberAccess(memberId, member.getHomeBranch().getId());

        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        Instant now = Instant.now(clock);
        MembershipSummary membership = membershipQuery.currentMembership(memberId);
        var attendance = new MemberDashboardResponse.Attendance(
                attendanceRepository.countMemberVisitsBetween(memberId, today.withDayOfMonth(1).atStartOfDay(zone).toInstant(), now),
                attendanceRepository.countMemberVisitsBetween(memberId, today.minusDays(30).atStartOfDay(zone).toInstant(), now),
                attendanceRepository.countByMemberId(memberId),
                attendanceRepository.findLastVisit(memberId).orElse(null),
                attendanceRepository.findOpenVisit(memberId).isPresent());
        WorkoutPlanResponse plan = workoutPlanRepository.findActiveForMember(memberId).map(WorkoutPlanResponse::from).orElse(null);
        AssignmentResponse trainer = assignmentRepository.findActiveByMemberId(memberId).map(AssignmentResponse::from).orElse(null);
        List<SessionResponse> sessions = sessionRepository.findUpcomingForMember(memberId, now).stream()
                .limit(5).map(SessionResponse::from).toList();
        List<PaymentResponse> payments = paymentRepository.findAll(
                (root, q, cb) -> cb.equal(root.get("member").get("id"), memberId),
                PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "processedAt"))).map(PaymentResponse::from).getContent();
        long unread = member.getUser() == null ? 0 : notificationRepository.countUnread(member.getUser().getId());
        return new MemberDashboardResponse(memberId, member.getMemberCode(), member.fullName(), membership,
                membership.endDate(), attendance, plan, trainer, sessions, payments, unread);
    }

    private static String currentBearerToken() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
        }
        return null;
    }
}
