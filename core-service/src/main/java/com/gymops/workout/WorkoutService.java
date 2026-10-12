package com.gymops.workout;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.api.PageResponse;
import com.gymops.common.event.DomainEvents;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.member.Member;
import com.gymops.member.MemberService;
import com.gymops.security.AccessPolicy;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import com.gymops.trainer.MemberTrainerRepository;
import com.gymops.trainer.Trainer;
import com.gymops.trainer.TrainerService;
import com.gymops.user.RoleName;
import com.gymops.workout.dto.WorkoutDtos.AssignPlanRequest;
import com.gymops.workout.dto.WorkoutDtos.ExerciseRequest;
import com.gymops.workout.dto.WorkoutDtos.ExerciseResponse;
import com.gymops.workout.dto.WorkoutDtos.PlanExerciseRequest;
import com.gymops.workout.dto.WorkoutDtos.ProgressRequest;
import com.gymops.workout.dto.WorkoutDtos.ProgressResponse;
import com.gymops.workout.dto.WorkoutDtos.WorkoutPlanRequest;
import com.gymops.workout.dto.WorkoutDtos.WorkoutPlanResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkoutService {

    private final ExerciseRepository exerciseRepository;
    private final WorkoutPlanRepository planRepository;
    private final MemberProgressRepository progressRepository;
    private final MemberTrainerRepository assignmentRepository;
    private final TrainerService trainerService;
    private final MemberService memberService;
    private final AccessPolicy access;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ------------------------------------------------------------------ exercise catalogue

    @Transactional
    public ExerciseResponse createExercise(ExerciseRequest r) {
        if (exerciseRepository.existsByName(r.name())) {
            throw new ConflictException("Exercise '" + r.name() + "' already exists");
        }
        return ExerciseResponse.from(exerciseRepository.save(new Exercise(r.name(), r.muscleGroup(), r.equipment(), r.description())));
    }

    @Transactional(readOnly = true)
    public PageResponse<ExerciseResponse> exercises(String muscleGroup, String q, Pageable pageable) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        String group = muscleGroup == null ? null : muscleGroup.toUpperCase();
        return PageResponse.of(exerciseRepository.search(group, like, pageable), ExerciseResponse::from);
    }

    // ------------------------------------------------------------------ templates

    @Transactional
    public WorkoutPlanResponse createTemplate(WorkoutPlanRequest r) {
        Trainer owner = trainerService.load(requireTrainerId());
        WorkoutPlan plan = WorkoutPlan.template(owner, r.name(), r.goal(), r.difficulty(), r.durationWeeks());
        plan.setDescription(r.description());
        plan.setTrainerNotes(r.trainerNotes());
        plan.replaceExercises(toExercises(r.exercises()));
        planRepository.save(plan);
        audit.record(AuditEvent.of("WORKOUT_PLAN", plan.getId(), "TEMPLATE_CREATED"));
        return WorkoutPlanResponse.from(plan);
    }

    @Transactional(readOnly = true)
    public List<WorkoutPlanResponse> templates() {
        return planRepository.findTemplates().stream().map(WorkoutPlanResponse::from).toList();
    }

    @Transactional
    public WorkoutPlanResponse update(Long planId, WorkoutPlanRequest r) {
        WorkoutPlan plan = loadPlan(planId);
        assertCanEdit(plan);
        plan.setName(r.name());
        plan.setDescription(r.description());
        plan.setGoal(r.goal());
        plan.setDifficulty(r.difficulty());
        plan.setDurationWeeks(r.durationWeeks());
        plan.setTrainerNotes(r.trainerNotes());
        List<WorkoutPlanExercise> exercises = toExercises(r.exercises());
        // remove old rows before inserting new ones: UNIQUE(workout_plan_id, day_number, position)
        plan.replaceExercises(List.of());
        planRepository.flush();
        plan.replaceExercises(exercises);
        if (plan.getStartDate() != null) {
            plan.setEndDate(plan.getStartDate().plusWeeks(r.durationWeeks()).minusDays(1));
        }
        audit.record(AuditEvent.of("WORKOUT_PLAN", planId, "UPDATED")
                .member(plan.getMember() == null ? null : plan.getMember().getId()));
        if (plan.isActiveMemberPlan()) {
            events.publishEvent(new DomainEvents.WorkoutPlanUpdated(planId, plan.getMember().getId()));
        }
        return WorkoutPlanResponse.from(plan);
    }

    // ------------------------------------------------------------------ member plans

    /** Copies a template into a personal ACTIVE plan for one of the trainer's clients; archives the previous plan. */
    @Transactional
    public WorkoutPlanResponse assign(Long templateId, AssignPlanRequest r) {
        WorkoutPlan template = loadPlan(templateId);
        Member member = memberService.load(r.memberId());
        Trainer trainer = assigningTrainer(member);
        planRepository.findActiveForMember(member.getId()).ifPresent(previous -> {
            previous.archive();
            planRepository.saveAndFlush(previous);
        });
        LocalDate start = r.startDate() != null ? r.startDate() : LocalDate.now(clock);
        WorkoutPlan plan = planRepository.saveAndFlush(template.assignTo(member, trainer, start));
        audit.record(AuditEvent.of("WORKOUT_PLAN", plan.getId(), "ASSIGNED").member(member.getId())
                .with("templateId", templateId).with("trainerId", trainer.getId()));
        events.publishEvent(new DomainEvents.WorkoutPlanAssigned(plan.getId(), member.getId()));
        return WorkoutPlanResponse.from(plan);
    }

    @Transactional
    public WorkoutPlanResponse complete(Long planId) {
        WorkoutPlan plan = loadPlan(planId);
        assertCanEdit(plan);
        plan.complete();
        audit.record(AuditEvent.of("WORKOUT_PLAN", planId, "COMPLETED").member(plan.getMember().getId()));
        return WorkoutPlanResponse.from(plan);
    }

    @Transactional(readOnly = true)
    public WorkoutPlanResponse get(Long planId) {
        WorkoutPlan plan = loadPlan(planId);
        if (!plan.isTemplate()) {
            assertMemberVisible(plan.getMember());
        }
        return WorkoutPlanResponse.from(plan);
    }

    @Transactional(readOnly = true)
    public Optional<WorkoutPlanResponse> activePlan(Long memberId) {
        assertMemberVisible(memberService.load(memberId));
        return planRepository.findActiveForMember(memberId).map(WorkoutPlanResponse::from);
    }

    @Transactional(readOnly = true)
    public List<WorkoutPlanResponse> memberPlans(Long memberId) {
        assertMemberVisible(memberService.load(memberId));
        return planRepository.findAllForMember(memberId).stream().map(WorkoutPlanResponse::from).toList();
    }

    // ------------------------------------------------------------------ progress

    @Transactional
    public ProgressResponse recordProgress(Long memberId, ProgressRequest r) {
        Member member = memberService.load(memberId);
        assertMemberVisible(member);
        Instant at = r.recordedAt() != null ? r.recordedAt() : Instant.now(clock);
        MemberProgress p = new MemberProgress(member, planRepository.findActiveForMember(memberId).orElse(null), at,
                CurrentUser.idOrNull());
        p.setWeightKg(r.weightKg());
        p.setBodyFatPct(r.bodyFatPct());
        p.setMuscleMassKg(r.muscleMassKg());
        p.setChestCm(r.chestCm());
        p.setWaistCm(r.waistCm());
        p.setHipsCm(r.hipsCm());
        p.setRestingHeartRate(r.restingHeartRate());
        p.setNotes(r.notes());
        return ProgressResponse.from(progressRepository.save(p));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProgressResponse> progress(Long memberId, Pageable pageable) {
        assertMemberVisible(memberService.load(memberId));
        return PageResponse.of(progressRepository.findByMemberIdOrderByRecordedAtDesc(memberId, pageable),
                ProgressResponse::from);
    }

    // ------------------------------------------------------------------ helpers

    private List<WorkoutPlanExercise> toExercises(List<PlanExerciseRequest> items) {
        Map<Long, Exercise> catalogue = exerciseRepository.findAllById(
                items.stream().map(PlanExerciseRequest::exerciseId).distinct().toList()).stream()
                .collect(Collectors.toMap(Exercise::getId, Function.identity()));
        List<WorkoutPlanExercise> result = new ArrayList<>();
        Map<Integer, Integer> positionByDay = new java.util.HashMap<>();
        for (PlanExerciseRequest i : items) {
            Exercise exercise = Optional.ofNullable(catalogue.get(i.exerciseId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Exercise", i.exerciseId()));
            int position = positionByDay.merge(i.dayNumber(), 1, Integer::sum);
            result.add(new WorkoutPlanExercise(exercise, i.dayNumber(), position, i.sets(), i.reps(), i.weightKg(),
                    i.durationSeconds(), i.restSeconds(), i.notes()));
        }
        return result;
    }

    private Long requireTrainerId() {
        Long trainerId = CurrentUser.get().trainerId();
        if (trainerId == null) {
            throw new BusinessRuleException("Only users with a trainer profile can create templates");
        }
        return trainerId;
    }

    /** The member's own trainer assigns plans; managers can assign on behalf of the member's trainer. */
    private Trainer assigningTrainer(Member member) {
        UserPrincipal caller = CurrentUser.get();
        var assignment = assignmentRepository.findActiveByMemberId(member.getId())
                .orElseThrow(() -> new BusinessRuleException("Member " + member.getMemberCode() + " has no assigned trainer"));
        Trainer trainer = assignment.getTrainer();
        if (Objects.equals(caller.trainerId(), trainer.getId())) {
            return trainer;
        }
        if (caller.hasAnyRole(List.of(RoleName.SUPER_ADMIN, RoleName.GYM_ADMIN, RoleName.MANAGER))) {
            access.assertBranchAccess(member.getHomeBranch().getId());
            return trainer;
        }
        throw new ForbiddenException("Only the member's trainer or a manager can assign a workout plan");
    }

    private void assertCanEdit(WorkoutPlan plan) {
        UserPrincipal caller = CurrentUser.get();
        if (Objects.equals(caller.trainerId(), plan.getTrainer().getId())) {
            return;
        }
        if (caller.hasAnyRole(List.of(RoleName.SUPER_ADMIN, RoleName.GYM_ADMIN, RoleName.MANAGER))
                && access.canAccessBranch(plan.getTrainer().getHomeBranch().getId())) {
            return;
        }
        throw new ForbiddenException("Only the owning trainer or a manager can edit this workout plan");
    }

    /** Member self, the member's current trainer, or non-trainer staff of the member's branch. */
    private void assertMemberVisible(Member member) {
        UserPrincipal caller = CurrentUser.get();
        if (caller.trainerId() != null && assignmentRepository.isActiveClient(caller.trainerId(), member.getId())) {
            return;
        }
        if (caller.hasRole(RoleName.TRAINER) && !caller.isOrgWide() && !caller.hasRole(RoleName.MANAGER)) {
            throw new ForbiddenException("Trainers can only access their own clients");
        }
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
    }

    private WorkoutPlan loadPlan(Long id) {
        return planRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Workout plan", id));
    }
}
