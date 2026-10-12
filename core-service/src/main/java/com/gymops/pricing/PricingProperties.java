package com.gymops.pricing;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Pricing and GST document settings ({@code app.pricing.*}).
 *
 * @param priceChangeNoticeDays  minimum days between a price-rise notice and the first autopay debit at the new price
 * @param maxOverrideDiscountPct largest negotiated discount (vs the list price) a MANAGER may give; admins are unlimited
 * @param noticeLookaheadDays    the daily sweep notifies autopay members whose renewal price rises within
 *                               lead days + notice days + this many days
 * @param legalBusinessName      registered legal name printed on tax invoices and credit notes
 * @param sacCode                services accounting code printed on invoice lines (999723 = fitness services)
 */
@Validated
@ConfigurationProperties(prefix = "app.pricing")
public record PricingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("7") @Min(0) @Max(90) int priceChangeNoticeDays,
        @DefaultValue("50") @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal maxOverrideDiscountPct,
        @DefaultValue("14") @Min(0) @Max(365) int noticeLookaheadDays,
        @DefaultValue("GymOps Fitness Private Limited") @NotBlank String legalBusinessName,
        @DefaultValue("999723") @NotBlank @Pattern(regexp = "^[0-9]{4,8}$") String sacCode) {
}
