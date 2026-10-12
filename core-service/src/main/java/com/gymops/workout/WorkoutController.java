package com.gymops.workout;

import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.workout.dto.WorkoutDtos.AssignPlanRequest;
import com.gymops.workout.dto.WorkoutDtos.ExerciseRequest;
import com.gymops.workout.dto.WorkoutDtos.ExerciseResponse;
import com.gymops.workout.dto.WorkoutDtos.ProgressRequest;
import com.gymops.workout.dto.WorkoutDtos.ProgressResponse;
import com.gymops.workout.dto.WorkoutDtos.WorkoutPlanRequest;
import com.gymops.workout.dto.WorkoutDtos.WorkoutPlanResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Workouts & progress")
@RestController
@RequestMapping("/api/v1/workouts")
@RequiredArgsConstructor
public class WorkoutController {

    private static final String COACHES = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','TRAINER')";

    private final WorkoutService service;

    @GetMapping("/exercises")
    public PageResponse<ExerciseResponse> exercises(@RequestParam(required = false) String muscleGroup,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "50") int size) {
        return service.exercises(muscleGroup, q, PageRequests.of(page, size, null, Set.of(), Sort.by("name")));
    }

    @PostMapping("/exercises")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(COACHES)
    public ExerciseResponse createExercise(@Valid @RequestBody ExerciseRequest request) {
        return service.createExercise(request);
    }

    @GetMapping("/templates")
    @PreAuthorize(COACHES)
    public List<WorkoutPlanResponse> templates() {
        return service.templates();
    }

    @Operation(summary = "Create a reusable workout template (trainer)")
    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TRAINER')")
    public WorkoutPlanResponse createTemplate(@Valid @RequestBody WorkoutPlanRequest request) {
        return service.createTemplate(request);
    }

    @Operation(summary = "Assign a template to a member (creates a personal copy)")
    @PostMapping("/templates/{templateId}/assign")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(COACHES)
    public WorkoutPlanResponse assign(@PathVariable Long templateId, @Valid @RequestBody AssignPlanRequest request) {
        return service.assign(templateId, request);
    }

    @GetMapping("/plans/{planId}")
    public WorkoutPlanResponse get(@PathVariable Long planId) {
        return service.get(planId);
    }

    @Operation(summary = "Update a template or a member's plan (exercises, sets/reps, trainer notes)")
    @PutMapping("/plans/{planId}")
    @PreAuthorize(COACHES)
    public WorkoutPlanResponse update(@PathVariable Long planId, @Valid @RequestBody WorkoutPlanRequest request) {
        return service.update(planId, request);
    }

    @PostMapping("/plans/{planId}/complete")
    @PreAuthorize(COACHES)
    public WorkoutPlanResponse complete(@PathVariable Long planId) {
        return service.complete(planId);
    }

    @GetMapping("/members/{memberId}/active-plan")
    public WorkoutPlanResponse activePlan(@PathVariable Long memberId) {
        return service.activePlan(memberId).orElseThrow(() -> new ResourceNotFoundException("Active workout plan for member", memberId));
    }

    @GetMapping("/members/{memberId}/plans")
    public List<WorkoutPlanResponse> memberPlans(@PathVariable Long memberId) {
        return service.memberPlans(memberId);
    }

    @PostMapping("/members/{memberId}/progress")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','TRAINER','MEMBER')")
    public ProgressResponse recordProgress(@PathVariable Long memberId, @Valid @RequestBody ProgressRequest request) {
        return service.recordProgress(memberId, request);
    }

    @GetMapping("/members/{memberId}/progress")
    public PageResponse<ProgressResponse> progress(@PathVariable Long memberId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return service.progress(memberId, PageRequests.of(page, size, null, Set.of(), Sort.unsorted()));
    }
}
