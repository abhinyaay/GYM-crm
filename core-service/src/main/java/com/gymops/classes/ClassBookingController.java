package com.gymops.classes;

import com.gymops.classes.dto.ClassDtos.AttendanceRequest;
import com.gymops.classes.dto.ClassDtos.BookingRequest;
import com.gymops.classes.dto.ClassDtos.BookingResponse;
import com.gymops.classes.dto.ClassDtos.CreditsResponse;
import com.gymops.classes.dto.ClassDtos.JobRunSummary;
import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.YearMonth;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Group classes")
@RestController
@RequestMapping("/api/v1/classes")
@RequiredArgsConstructor
public class ClassBookingController {

    private static final String BOOKERS = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','MEMBER')";
    private static final String STAFF = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','TRAINER')";

    /** When-filter for booking lists. */
    public enum When { UPCOMING, PAST }

    private final ClassBookingService service;
    private final ClassJobs jobs;

    @Operation(summary = "Book a class (members for themselves, front desk for a member)",
            description = "BOOKED when a seat is free, WAITLISTED when the class is full but the waitlist has room, "
                    + "otherwise 409. 422 when the membership, plan rules, monthly class credits, booking window or "
                    + "no-show block prevent booking.")
    @PostMapping("/sessions/{sessionId}/bookings")
    @PreAuthorize(BOOKERS)
    public ResponseEntity<BookingResponse> book(@PathVariable Long sessionId, @RequestBody(required = false) BookingRequest request) {
        BookingResponse booking = service.book(sessionId, request);
        return ResponseEntity.created(URI.create("/api/v1/classes/bookings/" + booking.id())).body(booking);
    }

    @Operation(summary = "Cancel a booking or leave the waitlist",
            description = "After the class's cancellation cutoff the result is LATE_CANCELLED and still uses a class credit. "
                    + "A freed seat is given to the first waitlisted member.")
    @PostMapping("/bookings/{id}/cancel")
    @PreAuthorize(BOOKERS)
    public BookingResponse cancel(@PathVariable Long id) {
        return service.cancel(id);
    }

    @Operation(summary = "Record attendance (ATTENDED / NO_SHOW) - instructor or front desk")
    @PatchMapping("/bookings/{id}/attendance")
    @PreAuthorize(STAFF)
    public BookingResponse attendance(@PathVariable Long id, @Valid @RequestBody AttendanceRequest request) {
        return service.markAttendance(id, request.status());
    }

    @Operation(summary = "The caller's own class bookings")
    @GetMapping("/bookings/me")
    @PreAuthorize("hasRole('MEMBER')")
    public PageResponse<BookingResponse> myBookings(@RequestParam(defaultValue = "UPCOMING") When when,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return service.myBookings(when == When.UPCOMING, PageRequests.of(page, size, null, Set.of(), Sort.unsorted()));
    }

    @Operation(summary = "A member's class bookings (staff)")
    @GetMapping("/members/{memberId}/bookings")
    @PreAuthorize(STAFF)
    public PageResponse<BookingResponse> memberBookings(@PathVariable Long memberId,
                                                        @RequestParam(defaultValue = "UPCOMING") When when,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return service.memberBookings(memberId, when == When.UPCOMING,
                PageRequests.of(page, size, null, Set.of(), Sort.unsorted()));
    }

    @Operation(summary = "The caller's class credits for a month (default: current month)")
    @GetMapping("/credits/me")
    @PreAuthorize("hasRole('MEMBER')")
    public CreditsResponse myCredits(@RequestParam(required = false) YearMonth month) {
        return service.myCredits(month);
    }

    @Operation(summary = "A member's class credits for a month (staff)")
    @GetMapping("/members/{memberId}/credits")
    @PreAuthorize(STAFF)
    public CreditsResponse memberCredits(@PathVariable Long memberId, @RequestParam(required = false) YearMonth month) {
        return service.memberCredits(memberId, month);
    }

    @Operation(summary = "Run the class completion/reconciliation and reminder job now")
    @PostMapping("/jobs/run")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public JobRunSummary runJobs() {
        return jobs.run();
    }
}
