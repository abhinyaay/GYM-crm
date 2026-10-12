package com.gymops.payment.dto;

import com.gymops.payment.RefundDestination;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** @param destination where the money goes; defaults to the original payment method */
public record RefundRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 300) String reason,
        RefundDestination destination) {
}
