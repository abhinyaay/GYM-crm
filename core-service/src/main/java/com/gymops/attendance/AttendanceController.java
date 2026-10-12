package com.gymops.attendance;

import com.gymops.attendance.dto.AttendanceDtos.AttendanceResponse;
import com.gymops.attendance.dto.AttendanceDtos.CheckInRequest;
import com.gymops.attendance.dto.AttendanceDtos.CheckOutRequest;
import com.gymops.attendance.dto.AttendanceDtos.DailySummary;
import com.gymops.attendance.dto.AttendanceDtos.Occupancy;
import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Attendance")
@RestController
@RequestMapping("/api/v1/attendance")
@RequiredArgsConstructor
public class AttendanceController {

    private static final String DESK_OR_SELF = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','MEMBER')";
    private static final String STAFF = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','TRAINER')";

    private final AttendanceService service;

    @Operation(summary = "Check a member in (validates membership, plan rules, opening hours, capacity, duplicates)")
    @PostMapping("/check-in")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(DESK_OR_SELF)
    public AttendanceResponse checkIn(@Valid @RequestBody CheckInRequest request) {
        return service.checkIn(request);
    }

    @PostMapping("/check-out")
    @PreAuthorize(DESK_OR_SELF)
    public AttendanceResponse checkOut(@Valid @RequestBody CheckOutRequest request) {
        return service.checkOut(request.memberId());
    }

    @Operation(summary = "Attendance history of a member")
    @GetMapping("/members/{memberId}")
    public PageResponse<AttendanceResponse> memberHistory(
            @PathVariable Long memberId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.memberHistory(memberId, from, to, PageRequests.of(page, size, null, Set.of(), Sort.unsorted()));
    }

    @Operation(summary = "Visits at a branch on a day (branch local date, default today)")
    @GetMapping("/branches/{branchId}")
    @PreAuthorize(STAFF)
    public PageResponse<AttendanceResponse> branchVisits(
            @PathVariable Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.branchVisits(branchId, date, PageRequests.of(page, size, null, Set.of(), Sort.unsorted()));
    }

    @Operation(summary = "Daily attendance summary for a branch")
    @GetMapping("/branches/{branchId}/daily-summary")
    @PreAuthorize(STAFF)
    public DailySummary dailySummary(@PathVariable Long branchId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.dailySummary(branchId, date);
    }

    @Operation(summary = "Live occupancy (members currently inside vs capacity)")
    @GetMapping("/branches/{branchId}/occupancy")
    @PreAuthorize(STAFF)
    public Occupancy occupancy(@PathVariable Long branchId) {
        return service.occupancy(branchId);
    }
}
