package com.gymops.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly typed, validated application configuration. Secrets (JWT secret, DB password,
 * bootstrap admin password) come exclusively from environment variables; the app fails fast at
 * startup if they are missing or too weak.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String businessTimezone,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @Valid @NotNull Security security,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Billing billing,
        @Valid @NotNull Attendance attendance,
        @Valid @NotNull Subscription subscription,
        @Valid @NotNull Complaints complaints,
        @Valid @NotNull Notifications notifications,
        @Valid @NotNull Analytics analytics,
        @Valid @NotNull Bootstrap bootstrap) {

    public record Security(
            @Valid @NotNull Jwt jwt,
            @Min(1) int maxFailedLogins,
            @NotNull Duration lockoutDuration,
            List<String> corsAllowedOrigins) {
    }

    public record Jwt(
            @NotBlank @Size(min = 32, message = "JWT secret must be at least 32 characters (256 bits)") String secret,
            @NotBlank String issuer,
            @NotBlank String audience,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl) {
    }

    public record RateLimit(boolean enabled, @Min(1) int authRequestsPerMinute) {
    }

    public record Billing(
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal taxRate,
            @Min(0) int invoiceDueDays,
            @Min(1) int pendingPaymentExpiryDays) {
    }

    public record Attendance(
            @NotNull Duration autoCheckoutAfter,
            @NotNull Duration assumedVisitDuration,
            @Min(1) int milestoneEvery) {
    }

    public record Subscription(@Min(1) int renewalReminderDays, @Min(1) int expiryWarningDays) {
    }

    public record Complaints(
            @NotNull Duration slaCritical,
            @NotNull Duration slaHigh,
            @NotNull Duration slaMedium,
            @NotNull Duration slaLow) {
    }

    public record Notifications(@Min(1) int dispatchBatchSize, @Min(1) int maxAttempts) {
    }

    public record Analytics(
            @NotBlank String baseUrl,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout) {
    }

    public record Bootstrap(String adminEmail, String adminPassword, boolean demoData, String demoPassword) {
    }
}
