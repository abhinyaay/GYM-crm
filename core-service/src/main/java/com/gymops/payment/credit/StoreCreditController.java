package com.gymops.payment.credit;

import com.gymops.payment.credit.StoreCreditService.CreditAccountResponse;
import com.gymops.payment.credit.StoreCreditService.CreditAdjustmentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Store credit")
@RestController
@RequestMapping("/api/v1/members/{memberId}/credit")
@RequiredArgsConstructor
public class StoreCreditController {

    private final StoreCreditService service;

    @Operation(summary = "Store-credit balance and the latest 50 ledger entries")
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','MEMBER')")
    public CreditAccountResponse get(@PathVariable Long memberId) {
        return service.account(memberId);
    }

    @Operation(summary = "Issue (positive) or correct (negative) store credit")
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')")
    public CreditAccountResponse adjust(@PathVariable Long memberId, @Valid @RequestBody CreditAdjustmentRequest request) {
        return service.adjust(memberId, request);
    }
}
