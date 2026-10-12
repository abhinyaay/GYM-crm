package com.gymops.access.dto;

import com.gymops.access.AccessAlarm;
import com.gymops.access.AccessCredential;
import com.gymops.access.AccessDevice;
import com.gymops.access.AccessEvent;
import com.gymops.access.AccessReason;
import com.gymops.access.AlarmType;
import com.gymops.access.BiometricConsent;
import com.gymops.access.CommandStatus;
import com.gymops.access.CommandType;
import com.gymops.access.ConsentCaptureMethod;
import com.gymops.access.CredentialStatus;
import com.gymops.access.CredentialType;
import com.gymops.access.Decision;
import com.gymops.access.DeviceCommand;
import com.gymops.access.DeviceDirection;
import com.gymops.access.DeviceStatus;
import com.gymops.access.DeviceType;
import com.gymops.access.Direction;
import com.gymops.access.SuspensionSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

public final class AccessDtos {

    private AccessDtos() {
    }

    // ------------------------------------------------------------------ devices (staff API)

    public record DeviceRequest(
            @NotNull Long branchId,
            @NotBlank @Size(max = 80) String name,
            @NotNull DeviceType type,
            @NotNull DeviceDirection direction,
            @Size(max = 60) String vendor,
            @Size(max = 60) String model,
            @Size(max = 80) String serialNumber,
            @Size(max = 40) String firmwareVersion) {
    }

    public record DeviceUpdateRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull DeviceType type,
            @NotNull DeviceDirection direction,
            @Size(max = 60) String vendor,
            @Size(max = 60) String model,
            @Size(max = 80) String serialNumber,
            @Size(max = 40) String firmwareVersion) {
    }

    public record DeviceStatusRequest(@NotNull DeviceStatus status) {
    }

    public record DeviceResponse(Long id, String deviceUid, Long branchId, String branchName, String name, DeviceType type,
                                 DeviceDirection direction, String vendor, String model, String serialNumber,
                                 String firmwareVersion, DeviceStatus status, int keyVersion, Instant secretIssuedAt,
                                 Instant lastHeartbeatAt, String lastSeenIp, Instant offlineSince, boolean online,
                                 Instant createdAt) {

        public static DeviceResponse from(AccessDevice d, boolean online) {
            return new DeviceResponse(d.getId(), d.getDeviceUid(), d.getBranch().getId(), d.getBranch().getName(),
                    d.getName(), d.getDeviceType(), d.getDirection(), d.getVendor(), d.getModel(), d.getSerialNumber(),
                    d.getFirmwareVersion(), d.getStatus(), d.getKeyVersion(), d.getSecretIssuedAt(), d.getLastHeartbeatAt(),
                    d.getLastSeenIp(), d.getOfflineSince(), online, d.getCreatedAt());
        }
    }

    /** Returned only by create and rotate-secret: the secret is shown once and never again. */
    public record DeviceSecretResponse(DeviceResponse device, String deviceSecret, String signatureScheme) {
    }

    public record CommandView(Long id, Long deviceId, CommandType type, CredentialType credentialType, Long credentialId,
                              String templateRef, String memberRef, CommandStatus status, int deliveryCount,
                              Instant createdAt, Instant deliveredAt, Instant completedAt, String resultMessage) {

        public static CommandView from(DeviceCommand c) {
            return new CommandView(c.getId(), c.getDevice().getId(), c.getCommandType(), c.getCredentialType(),
                    c.getCredentialId(), c.getTemplateRef(), c.getMemberRef(), c.getStatus(), c.getDeliveryCount(),
                    c.getCreatedAt(), c.getDeliveredAt(), c.getCompletedAt(), c.getResultMessage());
        }
    }

    // ------------------------------------------------------------------ credentials & consent

    public record CardEnrollRequest(
            @NotBlank @Size(max = 48) @Pattern(regexp = "[0-9A-Fa-f:\\- ]+", message = "card UID must be hexadecimal")
            String cardUid,
            @Size(max = 60) String label) {
    }

    public record BiometricEnrollRequest(@NotNull CredentialType type, @NotNull Long deviceId, @Size(max = 60) String label) {
        @AssertTrue(message = "type must be FINGERPRINT or FACE")
        public boolean isBiometricType() {
            return type == null || type.isBiometric();
        }
    }

    public record ReasonRequest(@Size(max = 200) String reason) {
    }

    public record CredentialResponse(Long id, Long memberId, CredentialType type, CredentialStatus status, String displayHint,
                                     String label, String templateRef, Long enrolledDeviceId, String enrolledDeviceName,
                                     Instant createdAt, Instant activatedAt, Instant suspendedAt,
                                     SuspensionSource suspensionSource, Instant revokedAt, String revokeReason,
                                     List<CommandView> commands) {

        public static CredentialResponse from(AccessCredential c, List<CommandView> commands) {
            return new CredentialResponse(c.getId(), c.getMember().getId(), c.getType(), c.getStatus(), c.getDisplayHint(),
                    c.getLabel(), c.getTemplateRef(),
                    c.getEnrolledDevice() == null ? null : c.getEnrolledDevice().getId(),
                    c.getEnrolledDevice() == null ? null : c.getEnrolledDevice().getName(),
                    c.getCreatedAt(), c.getActivatedAt(), c.getSuspendedAt(), c.getSuspensionSource(), c.getRevokedAt(),
                    c.getRevokeReason(), commands);
        }
    }

    /** Gate simulator support: an enrolled template / card a staff member can "present" at a simulated gate. */
    public record EnrolledCredential(Long credentialId, Long memberId, String memberCode, String memberName,
                                     CredentialType type, String templateRef, String displayHint) {
    }

    public record ConsentNotice(String version, String purpose, String text, String sha256) {
    }

    public record GrantConsentRequest(
            @NotBlank @Size(max = 20) String noticeVersion,
            boolean accepted,
            ConsentCaptureMethod captureMethod) {
        @AssertTrue(message = "the member must explicitly accept the consent notice")
        public boolean isAcceptedExplicitly() {
            return accepted;
        }
    }

    public record ConsentResponse(Long id, Long memberId, String purpose, String noticeVersion, String noticeSha256,
                                  ConsentCaptureMethod captureMethod, Instant grantedAt, Long grantedBy,
                                  Instant withdrawnAt, Long withdrawnBy, String withdrawalReason, boolean active) {

        public static ConsentResponse from(BiometricConsent c) {
            return new ConsentResponse(c.getId(), c.getMemberId(), c.getPurpose(), c.getConsentTextVersion(),
                    c.getConsentTextSha256(), c.getCaptureMethod(), c.getGrantedAt(), c.getGrantedBy(), c.getWithdrawnAt(),
                    c.getWithdrawnBy(), c.getWithdrawalReason(), c.isActive());
        }
    }

    public record ConsentStatus(Long memberId, boolean active, ConsentResponse current, List<ConsentResponse> history,
                                String currentNoticeVersion) {
    }

    /** Result of withdrawing consent: what was revoked and how many device deletion commands were queued. */
    public record ConsentWithdrawal(ConsentResponse consent, int credentialsRevoked, int deleteCommandsQueued) {
    }

    // ------------------------------------------------------------------ events, alarms, stats

    public record AccessEventResponse(Long id, Long deviceId, String deviceName, Long branchId, Long memberId, String memberCode,
                                      String memberName, CredentialType credentialType, String credentialHint,
                                      Direction direction, Decision decision, AccessReason reasonCode, String message,
                                      Long attendanceId, boolean offline, boolean antiPassbackViolation,
                                      Instant occurredAt, Instant receivedAt) {

        public static AccessEventResponse from(AccessEvent e, String deviceName, String memberCode, String memberName) {
            return new AccessEventResponse(e.getId(), e.getDeviceId(), deviceName, e.getBranchId(), e.getMemberId(), memberCode,
                    memberName, e.getCredentialType(), e.getCredentialHint(), e.getDirection(), e.getDecision(),
                    e.getReasonCode(), e.getMessage(), e.getAttendanceId(), e.isOffline(), e.isAntiPassbackViolation(),
                    e.getOccurredAt(), e.getReceivedAt());
        }
    }

    public record AlarmResponse(Long id, Long deviceId, String deviceName, Long branchId, AlarmType type, String details,
                                Instant occurredAt, Instant receivedAt, Instant acknowledgedAt, Long acknowledgedBy) {

        public static AlarmResponse from(AccessAlarm a, String deviceName) {
            return new AlarmResponse(a.getId(), a.getDeviceId(), deviceName, a.getBranchId(), a.getAlarmType(), a.getDetails(),
                    a.getOccurredAt(), a.getReceivedAt(), a.getAcknowledgedAt(), a.getAcknowledgedBy());
        }
    }

    public record AccessStats(Long branchId, LocalDate date, long entriesGranted, long exitsGranted, long denied,
                              long offlineEvents, Map<String, Long> deniedByReason, Map<String, Long> grantedByCredential,
                              long devices, long devicesOnline, long devicesOffline, long openAlarms) {
    }

    /** The member's rotating QR entry pass. Display {@code token} as a QR code; refresh before {@code expiresAt}. */
    public record EntryPass(String token, Long memberId, String memberCode, String memberName, Instant issuedAt,
                            Instant expiresAt, long windowSeconds, Instant serverTime) {
    }

    // ------------------------------------------------------------------ device (gate) API

    /**
     * One scan. {@code offlineDecision} is set only for events the gate decided by itself while offline (from its
     * signed allowlist) and uploads later; the server then reconciles instead of deciding.
     */
    public record ScanRequest(
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9._:-]+") String deviceEventId,
            @NotNull CredentialType credentialType,
            @NotBlank @Size(max = 128) String credentialValue,
            Direction direction,
            Instant occurredAt,
            Decision offlineDecision,
            AccessReason offlineReason) {
        @AssertTrue(message = "occurredAt is required for offline events")
        public boolean isOfflineTimed() {
            return offlineDecision == null || occurredAt != null;
        }
    }

    public record ScanBatchRequest(@NotEmpty @Size(max = 500) List<@Valid ScanRequest> events) {
    }

    public record GateDecision(Long eventId, String deviceEventId, Decision decision, AccessReason reasonCode, String message,
                               boolean openGate, int openSeconds, String memberDisplayName, Direction direction,
                               boolean offline, boolean antiPassbackViolation, boolean replayed, Instant serverTime) {
    }

    public record GateBatchResult(int processed, List<GateDecision> results) {
    }

    public record HeartbeatRequest(@Size(max = 40) String firmwareVersion, @Min(0) Long uptimeSeconds,
                                   @Min(0) @Max(1_000_000) Integer offlineQueueDepth, @Size(max = 20) String doorState) {
    }

    public record HeartbeatResponse(Instant serverTime, DeviceStatus deviceStatus, long pendingCommands,
                                    int allowlistRefreshSeconds) {
    }

    public record GateCommand(Long id, CommandType type, CredentialType credentialType, String templateRef,
                              String memberRef, Instant createdAt, int deliveryCount) {
    }

    public record GateCommandList(List<GateCommand> commands) {
    }

    public record CommandAck(
            boolean success,
            @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9._:-]*") String templateRef,
            @Size(max = 300) String message) {
    }

    public record AlarmReport(
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9._:-]+") String deviceEventId,
            @NotNull AlarmType type,
            Instant occurredAt,
            @Size(max = 300) String details) {
        @AssertTrue(message = "DEVICE_OFFLINE alarms are raised by the server")
        public boolean isDeviceReportable() {
            return type != AlarmType.DEVICE_OFFLINE;
        }
    }

    public record AlarmAck(Long alarmId, AlarmType type, boolean replayed) {
    }

    public record AllowlistHours(int dayOfWeek, LocalTime openTime, LocalTime closeTime, boolean closed) {
    }

    /**
     * One credential the gate may admit while offline, valid in [validFrom, validUntil), only between the plan's
     * daily access window (if any). Matching: SHA-256("RFID_CARD:" + UID) or SHA-256("TEMPLATE:" + type + ":" + ref).
     */
    public record AllowlistEntry(CredentialType credentialType, String credentialHash, String memberRef, String displayName,
                                 Instant validFrom, Instant validUntil, LocalTime dailyAccessStart, LocalTime dailyAccessEnd) {
    }

    public record Allowlist(String deviceId, Long branchId, String branchCode, String timezone, Instant generatedAt,
                            Instant validUntil, String hashScheme, List<AllowlistHours> openingHours,
                            List<AllowlistEntry> entries, String notes) {
    }
}
