package com.gymops.access;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Gate / access-control settings ({@code app.access.*}).
 *
 * @param enabled               switches the device API off entirely when false (devices get 404)
 * @param masterKey             ACCESS_MASTER_KEY: derives every device secret and the QR pass key. Required (32+ chars)
 *                              when the {@code prod} profile is active; elsewhere a fixed development key is used.
 * @param clockSkew             largest allowed difference between a device's X-Timestamp and the server clock
 * @param nonceTtl              how long a device nonce is remembered (must exceed twice the clock skew)
 * @param qrWindow              lifetime of one rotating QR token
 * @param qrWindowTolerance     neighbouring windows accepted at the gate (phone / gate clock drift)
 * @param antiPassback          HARD (deny) or SOFT (open, flag and repair the visit)
 * @param offlineAllowlistHours how far ahead the signed offline allowlist is valid
 * @param heartbeatTimeout      a device silent for longer is flagged offline (managers notified once per outage)
 * @param gateOpenSeconds       how long the gate should stay unlocked after a GRANTED decision
 * @param monitorInterval       how often the heartbeat monitor runs
 * @param defaultExitMode       exit mode of branches without their own setting: BUTTON (free exit, only entry is
 *                              verified) or VERIFIED (scan to exit, anti-passback)
 * @param buttonExitVisitLength in BUTTON mode nobody scans out, so open visits are closed this long after entry
 */
@Validated
@ConfigurationProperties(prefix = "app.access")
public record AccessProperties(
        @DefaultValue("true") boolean enabled,
        String masterKey,
        @DefaultValue("5m") @NotNull Duration clockSkew,
        @DefaultValue("15m") @NotNull Duration nonceTtl,
        @DefaultValue("30s") @NotNull Duration qrWindow,
        @DefaultValue("1") @Min(0) @Max(3) int qrWindowTolerance,
        @DefaultValue("HARD") @NotNull AntiPassbackMode antiPassback,
        @DefaultValue("24") @Min(1) @Max(168) int offlineAllowlistHours,
        @DefaultValue("5m") @NotNull Duration heartbeatTimeout,
        @DefaultValue("5") @Min(1) @Max(60) int gateOpenSeconds,
        @DefaultValue("1m") @NotNull Duration monitorInterval,
        @DefaultValue("BUTTON") @NotNull ExitMode defaultExitMode,
        @DefaultValue("2h") @NotNull Duration buttonExitVisitLength) {
}
