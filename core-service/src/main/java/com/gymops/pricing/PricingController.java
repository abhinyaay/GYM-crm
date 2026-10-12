package com.gymops.pricing;

import com.gymops.pricing.dto.PricingDtos.CancelRequest;
import com.gymops.pricing.dto.PricingDtos.PreviewResponse;
import com.gymops.pricing.dto.PricingDtos.PriceChangeRequest;
import com.gymops.pricing.dto.PricingDtos.PriceChangeResponse;
import com.gymops.pricing.dto.PricingDtos.PricingSettings;
import com.gymops.pricing.dto.PricingDtos.RunSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Pricing")
@RestController
@RequestMapping("/api/v1/pricing")
@RequiredArgsConstructor
public class PricingController {

    private static final String MANAGERS = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')";

    private final PriceChangeService service;
    private final PriceChangeJob job;
    private final PricingProperties props;

    @Operation(summary = "Price history of a plan (scheduled, applied and cancelled changes, newest first)")
    @GetMapping("/plans/{planId}/history")
    @PreAuthorize(MANAGERS)
    public List<PriceChangeResponse> history(@PathVariable Long planId) {
        return service.history(planId);
    }

    @Operation(summary = "Schedule a price / joining-fee change (effective today applies at once)",
            description = "existingMemberPolicy NEW_PRICE_AT_RENEWAL: existing members pay the new price from their "
                    + "next renewal invoiced on/after the effective date, autopay members only after the notice period. "
                    + "PROTECT_EXISTING: members holding the plan before the effective date keep their price until "
                    + "protectMonths / protectUntil; new sales pay the new price from the effective date.")
    @PostMapping("/plans/{planId}/changes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGERS)
    public PriceChangeResponse schedule(@PathVariable Long planId, @Valid @RequestBody PriceChangeRequest request) {
        return service.schedule(planId, request);
    }

    @Operation(summary = "Preview a price change: affected subscriptions, autopay members and first debit dates")
    @PostMapping("/plans/{planId}/changes/preview")
    @PreAuthorize(MANAGERS)
    public PreviewResponse preview(@PathVariable Long planId, @Valid @RequestBody PriceChangeRequest request) {
        return service.preview(planId, request);
    }

    @Operation(summary = "Cancel a scheduled price change (members already notified are told)")
    @PostMapping("/changes/{changeId}/cancel")
    @PreAuthorize(MANAGERS)
    public PriceChangeResponse cancel(@PathVariable Long changeId, @Valid @RequestBody CancelRequest request) {
        return service.cancel(changeId, request.reason());
    }

    @Operation(summary = "All scheduled (pending) price changes")
    @GetMapping("/changes/scheduled")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST')")
    public List<PriceChangeResponse> scheduled() {
        return service.scheduled();
    }

    @Operation(summary = "Pricing rules the UI needs (notice days, manager discount limit)")
    @GetMapping("/settings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST')")
    public PricingSettings settings() {
        return new PricingSettings(props.priceChangeNoticeDays(), props.maxOverrideDiscountPct(), props.sacCode(),
                props.legalBusinessName());
    }

    @Operation(summary = "Run the pricing job now: apply due changes and send price-rise notices (ops)")
    @PostMapping("/run")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN')")
    public RunSummary run() {
        return job.run();
    }
}
