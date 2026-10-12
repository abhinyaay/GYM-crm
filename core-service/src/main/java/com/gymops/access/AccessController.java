package com.gymops.access;

import com.gymops.access.AccessEventService.EventFilter;
import com.gymops.access.dto.AccessDtos.AccessEventResponse;
import com.gymops.access.dto.AccessDtos.AccessStats;
import com.gymops.access.dto.AccessDtos.AlarmResponse;
import com.gymops.access.dto.AccessDtos.BiometricEnrollRequest;
import com.gymops.access.dto.AccessDtos.CardEnrollRequest;
import com.gymops.access.dto.AccessDtos.CommandView;
import com.gymops.access.dto.AccessDtos.ConsentNotice;
import com.gymops.access.dto.AccessDtos.ConsentResponse;
import com.gymops.access.dto.AccessDtos.ConsentStatus;
import com.gymops.access.dto.AccessDtos.ConsentWithdrawal;
import com.gymops.access.dto.AccessDtos.CredentialResponse;
import com.gymops.access.dto.AccessDtos.DeviceRequest;
import com.gymops.access.dto.AccessDtos.DeviceResponse;
import com.gymops.access.dto.AccessDtos.DeviceSecretResponse;
import com.gymops.access.dto.AccessDtos.DeviceStatusRequest;
import com.gymops.access.dto.AccessDtos.DeviceUpdateRequest;
import com.gymops.access.dto.AccessDtos.EnrolledCredential;
import com.gymops.access.dto.AccessDtos.EntryPass;
import com.gymops.access.dto.AccessDtos.GrantConsentRequest;
import com.gymops.access.dto.AccessDtos.ReasonRequest;
import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff and member API of the access-control module (JWT). The device API lives in {@link GateController}. */
@Tag(name = "Access control")
@RestController
@RequestMapping("/api/v1/access")
@RequiredArgsConstructor
public class AccessController {

    private static final String DESK = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST')";
    private static final String MANAGERS = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')";
    private static final String DESK_OR_SELF = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','MEMBER')";
    private static final String MEMBER = "hasRole('MEMBER')";
    private static final Set<String> EVENT_SORT = Set.of("occurredAt", "id");

    private final DeviceService deviceService;
    private final CredentialService credentialService;
    private final ConsentService consentService;
    private final AccessEventService eventService;
    private final QrPassService qrPassService;

    // ------------------------------------------------------------------ devices

    @Operation(summary = "Gate devices visible to the caller (branch-scoped)")
    @GetMapping("/devices")
    @PreAuthorize(DESK)
    public List<DeviceResponse> devices(@RequestParam(required = false) Long branchId) {
        return deviceService.list(branchId);
    }

    @GetMapping("/devices/{id}")
    @PreAuthorize(DESK)
    public DeviceResponse device(@PathVariable Long id) {
        return deviceService.get(id);
    }

    @Operation(summary = "Register a gate device; the response contains the device secret ONCE")
    @PostMapping("/devices")
    @PreAuthorize(MANAGERS)
    public ResponseEntity<DeviceSecretResponse> createDevice(@Valid @RequestBody DeviceRequest request) {
        DeviceSecretResponse created = deviceService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/access/devices/" + created.device().id())).body(created);
    }

    @PutMapping("/devices/{id}")
    @PreAuthorize(MANAGERS)
    public DeviceResponse updateDevice(@PathVariable Long id, @Valid @RequestBody DeviceUpdateRequest request) {
        return deviceService.update(id, request);
    }

    @Operation(summary = "Rotate the device secret (old secret stops working immediately); new secret returned once")
    @PostMapping("/devices/{id}/rotate-secret")
    @PreAuthorize(MANAGERS)
    public DeviceSecretResponse rotateSecret(@PathVariable Long id) {
        return deviceService.rotateSecret(id);
    }

    @PatchMapping("/devices/{id}/status")
    @PreAuthorize(MANAGERS)
    public DeviceResponse deviceStatus(@PathVariable Long id, @Valid @RequestBody DeviceStatusRequest request) {
        return deviceService.changeStatus(id, request.status());
    }

    @Operation(summary = "Recent commands queued for a device (enrol / delete template)")
    @GetMapping("/devices/{id}/commands")
    @PreAuthorize(DESK)
    public List<CommandView> deviceCommands(@PathVariable Long id, @RequestParam(defaultValue = "20") int limit) {
        return deviceService.recentCommands(id, limit);
    }

    // ------------------------------------------------------------------ credentials

    @GetMapping("/members/{memberId}/credentials")
    @PreAuthorize(DESK_OR_SELF)
    public List<CredentialResponse> credentials(@PathVariable Long memberId) {
        return credentialService.list(memberId);
    }

    @Operation(summary = "Register an RFID card (only a SHA-256 of the UID is stored)")
    @PostMapping("/members/{memberId}/credentials/card")
    @PreAuthorize(DESK)
    public ResponseEntity<CredentialResponse> enrollCard(@PathVariable Long memberId,
                                                         @Valid @RequestBody CardEnrollRequest request) {
        CredentialResponse created = credentialService.enrollCard(memberId, request);
        return ResponseEntity.created(URI.create("/api/v1/access/members/" + memberId + "/credentials")).body(created);
    }

    @Operation(summary = "Start fingerprint / face enrolment on a device (requires recorded consent)")
    @PostMapping("/members/{memberId}/credentials/biometric")
    @PreAuthorize(DESK)
    public ResponseEntity<CredentialResponse> enrollBiometric(@PathVariable Long memberId,
                                                              @Valid @RequestBody BiometricEnrollRequest request) {
        CredentialResponse created = credentialService.requestBiometricEnrollment(memberId, request);
        return ResponseEntity.created(URI.create("/api/v1/access/members/" + memberId + "/credentials")).body(created);
    }

    @Operation(summary = "Re-issue the member's rotating QR pass after it was revoked")
    @PostMapping("/members/{memberId}/credentials/qr")
    @PreAuthorize(DESK)
    public ResponseEntity<CredentialResponse> issueQr(@PathVariable Long memberId) {
        CredentialResponse created = credentialService.issueQrPass(memberId);
        return ResponseEntity.created(URI.create("/api/v1/access/members/" + memberId + "/credentials")).body(created);
    }

    @PostMapping("/credentials/{id}/revoke")
    @PreAuthorize(DESK)
    public CredentialResponse revoke(@PathVariable Long id, @Valid @RequestBody(required = false) ReasonRequest request) {
        return credentialService.revoke(id, request == null ? null : request.reason());
    }

    @PostMapping("/credentials/{id}/suspend")
    @PreAuthorize(DESK)
    public CredentialResponse suspend(@PathVariable Long id) {
        return credentialService.suspend(id);
    }

    @PostMapping("/credentials/{id}/reactivate")
    @PreAuthorize(DESK)
    public CredentialResponse reactivate(@PathVariable Long id) {
        return credentialService.reactivate(id);
    }

    @Operation(summary = "Gate simulator: active biometric templates / cards of members whose home branch is branchId")
    @GetMapping("/credentials/enrolled")
    @PreAuthorize(DESK)
    public List<EnrolledCredential> enrolled(@RequestParam Long branchId,
                                             @RequestParam(required = false) Set<CredentialType> types) {
        return credentialService.enrolledAtBranch(branchId, types == null || types.isEmpty()
                ? EnumSet.of(CredentialType.FINGERPRINT, CredentialType.FACE) : types);
    }

    // ------------------------------------------------------------------ consent

    @Operation(summary = "Current biometric consent notice (show it before asking for consent)")
    @GetMapping("/consent/notice")
    @PreAuthorize(DESK_OR_SELF)
    public ConsentNotice notice() {
        return consentService.notice();
    }

    @GetMapping("/members/{memberId}/consent")
    @PreAuthorize(DESK_OR_SELF)
    public ConsentStatus consent(@PathVariable Long memberId) {
        return consentService.status(memberId);
    }

    @Operation(summary = "Record the member's explicit biometric consent (notice version is recorded)")
    @PostMapping("/members/{memberId}/consent")
    @PreAuthorize(DESK_OR_SELF)
    public ResponseEntity<ConsentResponse> grantConsent(@PathVariable Long memberId,
                                                        @Valid @RequestBody GrantConsentRequest request) {
        ConsentResponse created = consentService.grant(memberId, request);
        return ResponseEntity.created(URI.create("/api/v1/access/members/" + memberId + "/consent")).body(created);
    }

    @Operation(summary = "Withdraw biometric consent: revokes biometric credentials and queues template deletion")
    @PostMapping("/members/{memberId}/consent/withdraw")
    @PreAuthorize(DESK_OR_SELF)
    public ConsentWithdrawal withdrawConsent(@PathVariable Long memberId,
                                             @Valid @RequestBody(required = false) ReasonRequest request) {
        return consentService.withdraw(memberId, request == null ? null : request.reason());
    }

    // ------------------------------------------------------------------ events, stats, alarms

    @Operation(summary = "Search the access event log (branch-scoped, paginated)")
    @GetMapping("/events")
    @PreAuthorize(DESK)
    public PageResponse<AccessEventResponse> events(
            @RequestParam(required = false) Long branchId, @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) Long memberId, @RequestParam(required = false) Decision decision,
            @RequestParam(required = false) Direction direction, @RequestParam(required = false) AccessReason reason,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return eventService.search(new EventFilter(branchId, deviceId, memberId, decision, direction, reason, from, to),
                PageRequests.of(page, size, sort, EVENT_SORT, Sort.by(Sort.Direction.DESC, "occurredAt", "id")));
    }

    @Operation(summary = "Live feed: latest events (newest first); afterId returns only newer events")
    @GetMapping("/events/feed")
    @PreAuthorize(DESK)
    public List<AccessEventResponse> feed(@RequestParam(required = false) Long branchId,
                                          @RequestParam(defaultValue = "0") long afterId,
                                          @RequestParam(defaultValue = "25") int limit) {
        return eventService.feed(branchId, afterId, limit);
    }

    @GetMapping("/members/{memberId}/events")
    @PreAuthorize(DESK_OR_SELF)
    public PageResponse<AccessEventResponse> memberEvents(@PathVariable Long memberId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return eventService.memberEvents(memberId, PageRequests.of(page, size, null, Set.of(),
                Sort.by(Sort.Direction.DESC, "occurredAt", "id")));
    }

    @Operation(summary = "Today's (or a given day's) entries, exits, denials by reason, device health, open alarms")
    @GetMapping("/stats")
    @PreAuthorize(DESK)
    public AccessStats stats(@RequestParam(required = false) Long branchId,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return eventService.stats(branchId, date);
    }

    @GetMapping("/alarms")
    @PreAuthorize(DESK)
    public List<AlarmResponse> alarms(@RequestParam(required = false) Long branchId,
                                      @RequestParam(defaultValue = "false") boolean openOnly,
                                      @RequestParam(defaultValue = "50") int limit) {
        return eventService.alarms(branchId, openOnly, limit);
    }

    @PostMapping("/alarms/{id}/acknowledge")
    @PreAuthorize(DESK)
    public AlarmResponse acknowledge(@PathVariable Long id) {
        return eventService.acknowledge(id);
    }

    // ------------------------------------------------------------------ member self-service

    @Operation(summary = "My rotating QR entry pass (refresh every window; single use per window at the gate)")
    @GetMapping("/me/pass")
    @PreAuthorize(MEMBER)
    public EntryPass myPass() {
        return qrPassService.issue(myMemberId());
    }

    @GetMapping("/me/events")
    @PreAuthorize(MEMBER)
    public PageResponse<AccessEventResponse> myEvents(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return memberEvents(myMemberId(), page, size);
    }

    @GetMapping("/me/credentials")
    @PreAuthorize(MEMBER)
    public List<CredentialResponse> myCredentials() {
        return credentialService.list(myMemberId());
    }

    @GetMapping("/me/consent")
    @PreAuthorize(MEMBER)
    public ConsentStatus myConsent() {
        return consentService.status(myMemberId());
    }

    @PostMapping("/me/consent")
    @PreAuthorize(MEMBER)
    public ResponseEntity<ConsentResponse> grantMyConsent(@Valid @RequestBody GrantConsentRequest request) {
        return grantConsent(myMemberId(), request);
    }

    @PostMapping("/me/consent/withdraw")
    @PreAuthorize(MEMBER)
    public ConsentWithdrawal withdrawMyConsent(@Valid @RequestBody(required = false) ReasonRequest request) {
        return withdrawConsent(myMemberId(), request);
    }

    private static Long myMemberId() {
        UserPrincipal caller = CurrentUser.get();
        if (caller.memberId() == null) {
            throw new ResourceNotFoundException("Member profile for user", caller.userId());
        }
        return caller.memberId();
    }
}
