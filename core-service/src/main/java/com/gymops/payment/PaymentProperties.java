package com.gymops.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Payment provider and recurring-billing configuration ({@code app.payments.*}).
 * Razorpay credentials come only from the environment; nothing is defaulted.
 */
@Validated
@ConfigurationProperties(prefix = "app.payments")
public record PaymentProperties(
        @NotNull PaymentProvider gateway,
        String simulatedSecret,
        @Valid Razorpay razorpay,
        @NotNull @DecimalMin("1.00") BigDecimal mandateMaxAmount,
        @NotNull @DecimalMin("0.00") BigDecimal emiMinAmount,
        @NotEmpty List<Integer> emiTenures,
        @NotNull Duration checkoutExpiry,
        @Valid @NotNull Autopay autopay) {

    public record Razorpay(String keyId, String keySecret, String webhookSecret, String baseUrl, String businessName) {
    }

    /**
     * @param leadDays         how many days before the term ends the renewal invoice is raised
     * @param preDebitNotice   minimum notice between the pre-debit notification and the charge (RBI e-mandate rule)
     * @param retryDelaysDays  delays before retries 2, 3, ... after a failed debit (dunning)
     */
    public record Autopay(@Min(1) int leadDays, @NotNull Duration preDebitNotice, @NotEmpty List<Integer> retryDelaysDays,
                          String cron) {
    }
}
