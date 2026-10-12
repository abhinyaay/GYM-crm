package com.gymops.subscription;

import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import com.gymops.subscription.dto.CancellationResponse;
import com.gymops.subscription.dto.SubscriptionRequests;
import com.gymops.subscription.dto.SubscriptionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Subscriptions")
@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private static final String FRONT_DESK_OR_SELF = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','MEMBER')";
    private static final Set<String> SORTABLE = Set.of("startDate", "endDate", "createdAt");

    private final SubscriptionService service;
    private final SubscriptionLifecycleJob lifecycleJob;

    @Operation(summary = "Purchase a plan: creates a PENDING_PAYMENT subscription and its invoice")
    @PostMapping
    @PreAuthorize(FRONT_DESK_OR_SELF)
    public ResponseEntity<SubscriptionResponse> subscribe(@Valid @RequestBody SubscriptionRequests.Create request) {
        SubscriptionResponse created = service.subscribe(request);
        return ResponseEntity.created(URI.create("/api/v1/subscriptions/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public SubscriptionResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @Operation(summary = "Search subscriptions (e.g. status=ACTIVE&endingTo=2026-11-01 for upcoming expiries)")
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST')")
    public PageResponse<SubscriptionResponse> search(
            @RequestParam(required = false) Long branchId, @RequestParam(required = false) SubscriptionStatus status,
            @RequestParam(required = false) Long planId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endingFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endingTo,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return service.search(branchId, status, planId, endingFrom, endingTo,
                PageRequests.of(page, size, sort, SORTABLE, Sort.by("endDate")));
    }

    @Operation(summary = "Subscription history of a member")
    @GetMapping("/members/{memberId}")
    public PageResponse<SubscriptionResponse> history(@PathVariable Long memberId,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return service.history(memberId, PageRequests.of(page, size, null, SORTABLE, Sort.unsorted()));
    }

    @Operation(summary = "Renew: new term starts the day after the current one ends")
    @PostMapping("/{id}/renew")
    @PreAuthorize(FRONT_DESK_OR_SELF)
    public ResponseEntity<SubscriptionResponse> renew(@PathVariable Long id,
                                                      @Valid @RequestBody(required = false) SubscriptionRequests.Renew request) {
        SubscriptionResponse created = service.renew(id, request == null ? new SubscriptionRequests.Renew(null, null) : request);
        return ResponseEntity.created(URI.create("/api/v1/subscriptions/" + created.id())).body(created);
    }

    @Operation(summary = "Upgrade (immediate, prorated credit) or downgrade (from next term)")
    @PostMapping("/{id}/change-plan")
    @PreAuthorize(FRONT_DESK_OR_SELF)
    public ResponseEntity<SubscriptionResponse> changePlan(@PathVariable Long id,
                                                           @Valid @RequestBody SubscriptionRequests.ChangePlan request) {
        SubscriptionResponse created = service.changePlan(id, request);
        return ResponseEntity.created(URI.create("/api/v1/subscriptions/" + created.id())).body(created);
    }

    @PostMapping("/{id}/freeze")
    @PreAuthorize(FRONT_DESK_OR_SELF)
    public SubscriptionResponse freeze(@PathVariable Long id, @Valid @RequestBody SubscriptionRequests.Freeze request) {
        return service.freeze(id, request);
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize(FRONT_DESK_OR_SELF)
    public SubscriptionResponse resume(@PathVariable Long id) {
        return service.resume(id);
    }

    @Operation(summary = "Cancel; refund computed from the plan's cancellation policy")
    @PostMapping("/{id}/cancel")
    @PreAuthorize(FRONT_DESK_OR_SELF)
    public CancellationResponse cancel(@PathVariable Long id, @Valid @RequestBody SubscriptionRequests.Cancel request) {
        return service.cancel(id, request);
    }

    @Operation(summary = "Run the daily lifecycle job now (start / grace / expiry / auto-resume / reminders)")
    @PostMapping("/lifecycle/run")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public SubscriptionLifecycleJob.RunSummary runLifecycle() {
        return lifecycleJob.run();
    }
}
