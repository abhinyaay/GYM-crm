package com.gymops.classes;

import com.gymops.classes.dto.ClassDtos.CancelSeriesRequest;
import com.gymops.classes.dto.ClassDtos.CancelSessionRequest;
import com.gymops.classes.dto.ClassDtos.ClassTypeRequest;
import com.gymops.classes.dto.ClassDtos.ClassTypeResponse;
import com.gymops.classes.dto.ClassDtos.SeriesCancelResponse;
import com.gymops.classes.dto.ClassDtos.SeriesRequest;
import com.gymops.classes.dto.ClassDtos.SeriesResponse;
import com.gymops.classes.dto.ClassDtos.SessionDetailResponse;
import com.gymops.classes.dto.ClassDtos.SessionRequest;
import com.gymops.classes.dto.ClassDtos.SessionResponse;
import com.gymops.classes.dto.ClassDtos.SessionUpdateRequest;
import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Group classes")
@RestController
@RequestMapping("/api/v1/classes")
@RequiredArgsConstructor
public class ClassController {

    static final String MANAGERS = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')";

    private final ClassTypeService typeService;
    private final ClassScheduleService scheduleService;

    // ------------------------------------------------------------------ class types

    @Operation(summary = "Class catalogue (active types; managers may include inactive ones)")
    @GetMapping("/types")
    public List<ClassTypeResponse> types(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return typeService.list(includeInactive);
    }

    @GetMapping("/types/{id}")
    public ClassTypeResponse type(@PathVariable Long id) {
        return typeService.get(id);
    }

    @PostMapping("/types")
    @PreAuthorize(MANAGERS)
    public ResponseEntity<ClassTypeResponse> createType(@Valid @RequestBody ClassTypeRequest request) {
        ClassTypeResponse created = typeService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/classes/types/" + created.id())).body(created);
    }

    @PutMapping("/types/{id}")
    @PreAuthorize(MANAGERS)
    public ClassTypeResponse updateType(@PathVariable Long id, @Valid @RequestBody ClassTypeRequest request) {
        return typeService.update(id, request);
    }

    @Operation(summary = "Delete an unused class type (types with classes must be deactivated instead)")
    @DeleteMapping("/types/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(MANAGERS)
    public void deleteType(@PathVariable Long id) {
        typeService.delete(id);
    }

    // ------------------------------------------------------------------ sessions

    @Operation(summary = "Timetable search with booking counts",
            description = "Dates are inclusive business dates (default: today + 6 days for staff, + 14 days for members). "
                    + "Members only see upcoming classes at branches their plan admits them to, with their own booking status. "
                    + "Trainers can pass mine=true for the classes they teach.")
    @GetMapping("/sessions")
    @SuppressWarnings("checkstyle:ParameterNumber")
    public PageResponse<SessionResponse> sessions(@RequestParam(required = false) Long branchId,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                  LocalDate from,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                  LocalDate to,
                                                  @RequestParam(required = false) Long classTypeId,
                                                  @RequestParam(required = false) Long instructorId,
                                                  @RequestParam(required = false) ClassSessionStatus status,
                                                  @RequestParam(defaultValue = "false") boolean mine,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        return scheduleService.search(branchId, from, to, classTypeId, instructorId, status, mine,
                PageRequests.of(page, size, null, Set.of(), Sort.by("startsAt", "id")));
    }

    @Operation(summary = "Class detail; the roster is included for branch staff and the instructor")
    @GetMapping("/sessions/{id}")
    public SessionDetailResponse session(@PathVariable Long id) {
        return scheduleService.get(id);
    }

    @Operation(summary = "Schedule a single class")
    @PostMapping("/sessions")
    @PreAuthorize(MANAGERS)
    public ResponseEntity<SessionResponse> create(@Valid @RequestBody SessionRequest request) {
        SessionResponse created = scheduleService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/classes/sessions/" + created.id())).body(created);
    }

    @Operation(summary = "Schedule a weekly recurring series; clashing or past slots are skipped and reported")
    @PostMapping("/sessions/series")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGERS)
    public SeriesResponse createSeries(@Valid @RequestBody SeriesRequest request) {
        return scheduleService.createSeries(request);
    }

    @Operation(summary = "Edit time, duration, instructor, room, capacity or policy of a class that has not started")
    @PutMapping("/sessions/{id}")
    @PreAuthorize(MANAGERS)
    public SessionResponse update(@PathVariable Long id, @Valid @RequestBody SessionUpdateRequest request) {
        return scheduleService.update(id, request);
    }

    @Operation(summary = "Cancel a class: releases every booking and notifies the members")
    @PostMapping("/sessions/{id}/cancel")
    @PreAuthorize(MANAGERS)
    public SessionResponse cancel(@PathVariable Long id, @Valid @RequestBody CancelSessionRequest request) {
        return scheduleService.cancel(id, request.reason());
    }

    @Operation(summary = "Cancel every upcoming class of a series from a date")
    @PostMapping("/series/{seriesId}/cancel")
    @PreAuthorize(MANAGERS)
    public SeriesCancelResponse cancelSeries(@PathVariable UUID seriesId, @Valid @RequestBody CancelSeriesRequest request) {
        return scheduleService.cancelSeries(seriesId, request);
    }
}
