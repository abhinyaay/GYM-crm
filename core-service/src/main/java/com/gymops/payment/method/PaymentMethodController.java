package com.gymops.payment.method;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Saved payment methods")
@RestController
@RequestMapping("/api/v1/members/{memberId}/payment-methods")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','MEMBER')")
@RequiredArgsConstructor
public class PaymentMethodController {

    private final PaymentMethodService service;

    @Operation(summary = "Saved cards / UPI handles (tokens only; no card numbers are stored)")
    @GetMapping
    public List<SavedPaymentMethodResponse> list(@PathVariable Long memberId) {
        return service.list(memberId);
    }

    @PutMapping("/{id}/default")
    public List<SavedPaymentMethodResponse> makeDefault(@PathVariable Long memberId, @PathVariable Long id) {
        return service.makeDefault(memberId, id);
    }

    @Operation(summary = "Remove a saved method; autopay using it is switched off")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long memberId, @PathVariable Long id) {
        service.revoke(memberId, id);
    }
}
