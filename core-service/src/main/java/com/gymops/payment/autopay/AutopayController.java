package com.gymops.payment.autopay;

import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import com.gymops.payment.autopay.AutopayDtos.AttemptResponse;
import com.gymops.payment.autopay.AutopayDtos.AutopayRequest;
import com.gymops.payment.autopay.AutopayDtos.RunSummary;
import com.gymops.payment.autopay.AutopayDtos.UpcomingRenewal;
import com.gymops.subscription.dto.SubscriptionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Autopay (recurring billing)")
@Validated
@RestController
@RequiredArgsConstructor
public class AutopayController {

    private static final String STAFF = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST')";
    private static final Set<String> SORT = Set.of("scheduledAt", "createdAt");

    private final AutopayService service;
    private final AutopayJob job;

    @Operation(summary = "Turn autopay on (with a saved method that has an active mandate) or off")
    @PutMapping("/api/v1/subscriptions/{id}/autopay")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','MEMBER')")
    public SubscriptionResponse setAutopay(@PathVariable Long id, @Valid @RequestBody AutopayRequest request) {
        return service.setAutopay(id, request);
    }

    @Operation(summary = "Autopay debit attempts (members see only their own)")
    @GetMapping("/api/v1/autopay/attempts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','MEMBER')")
    public PageResponse<AttemptResponse> attempts(
            @RequestParam(required = false) Long memberId, @RequestParam(required = false) Long subscriptionId,
            @RequestParam(required = false) AutopayStatus status, @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "scheduledAt,desc") String sort) {
        return service.search(memberId, subscriptionId, status, branchId,
                PageRequests.of(page, size, sort, SORT, Sort.by(Sort.Direction.DESC, "scheduledAt")));
    }

    @Operation(summary = "Autopay subscriptions renewing within the next N days")
    @GetMapping("/api/v1/autopay/upcoming")
    @PreAuthorize(STAFF)
    public List<UpcomingRenewal> upcoming(@RequestParam(defaultValue = "14") @Min(1) @Max(90) int days,
                                          @RequestParam(required = false) Long branchId) {
        return service.upcoming(days, branchId);
    }

    @Operation(summary = "Retry the latest failed debit now")
    @PostMapping("/api/v1/autopay/attempts/{id}/retry")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')")
    public AttemptResponse retry(@PathVariable Long id) {
        return service.retry(id);
    }

    @Operation(summary = "Run the autopay job now (normally hourly)")
    @PostMapping("/api/v1/autopay/run")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN')")
    public RunSummary run() {
        return job.run();
    }
}
