package com.gymops.trainer;

import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Trainers")
@RestController
@RequestMapping("/api/v1/trainers")
@RequiredArgsConstructor
public class TrainerController {

    private static final String MANAGERS = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')";
    private static final String MANAGERS_OR_TRAINER = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','TRAINER')";

    private final TrainerService service;

    @GetMapping
    public PageResponse<TrainerResponse> search(@RequestParam(required = false) Long branchId,
                                                @RequestParam(required = false) TrainerStatus status,
                                                @RequestParam(required = false) String specialization,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return service.search(branchId, status, specialization,
                PageRequests.of(page, size, null, Set.of(), Sort.by("employeeCode")));
    }

    @GetMapping("/{id}")
    public TrainerResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @Operation(summary = "Create a trainer profile for a user holding the TRAINER role")
    @PostMapping
    @PreAuthorize(MANAGERS)
    public ResponseEntity<TrainerResponse> create(@Valid @RequestBody TrainerRequest request) {
        TrainerResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/trainers/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize(MANAGERS_OR_TRAINER)
    public TrainerResponse update(@PathVariable Long id, @Valid @RequestBody TrainerUpdateRequest request) {
        return service.update(id, request);
    }

    @Operation(summary = "Replace the trainer's weekly availability")
    @PutMapping("/{id}/availability")
    @PreAuthorize(MANAGERS_OR_TRAINER)
    public TrainerResponse availability(@PathVariable Long id, @Valid @RequestBody AvailabilityRequest request) {
        return service.replaceAvailability(id, request);
    }

    @Operation(summary = "Assign a member to the trainer (ends any previous trainer assignment)")
    @PostMapping("/{id}/clients")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST')")
    public AssignmentResponse assign(@PathVariable Long id, @Valid @RequestBody AssignMemberRequest request) {
        return service.assignMember(id, request);
    }

    @GetMapping("/{id}/clients")
    @PreAuthorize(MANAGERS_OR_TRAINER)
    public List<AssignmentResponse> clients(@PathVariable Long id) {
        return service.clients(id);
    }

    @DeleteMapping("/{id}/clients/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(MANAGERS_OR_TRAINER)
    public void endAssignment(@PathVariable Long id, @PathVariable Long memberId) {
        service.endAssignment(id, memberId);
    }

    @Operation(summary = "Book a session with a client inside the trainer's availability")
    @PostMapping("/{id}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGERS_OR_TRAINER)
    public SessionResponse schedule(@PathVariable Long id, @Valid @RequestBody SessionRequest request) {
        return service.scheduleSession(id, request);
    }

    @PatchMapping("/{id}/sessions/{sessionId}")
    @PreAuthorize(MANAGERS_OR_TRAINER)
    public SessionResponse sessionStatus(@PathVariable Long id, @PathVariable Long sessionId,
                                         @Valid @RequestBody SessionStatusRequest request) {
        return service.updateSessionStatus(id, sessionId, request);
    }

    @Operation(summary = "Trainer schedule (default: next 7 days)")
    @GetMapping("/{id}/schedule")
    @PreAuthorize(MANAGERS_OR_TRAINER)
    public List<SessionResponse> schedule(@PathVariable Long id,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.schedule(id, from, to);
    }

    @Operation(summary = "Session counts and completion / no-show rates (default: last 30 days)")
    @GetMapping("/{id}/performance")
    @PreAuthorize(MANAGERS_OR_TRAINER)
    public PerformanceResponse performance(@PathVariable Long id,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.performance(id, from, to);
    }
}
