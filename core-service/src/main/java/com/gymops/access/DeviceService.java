package com.gymops.access;

import com.gymops.access.dto.AccessDtos.CommandView;
import com.gymops.access.dto.AccessDtos.DeviceRequest;
import com.gymops.access.dto.AccessDtos.DeviceResponse;
import com.gymops.access.dto.AccessDtos.DeviceSecretResponse;
import com.gymops.access.dto.AccessDtos.DeviceUpdateRequest;
import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.branch.BranchService;
import com.gymops.branch.GymBranch;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.security.AccessPolicy;
import com.gymops.security.BranchScope;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Device administration for staff: register, describe, enable / disable, rotate the shared secret. */
@Service
@RequiredArgsConstructor
public class DeviceService {

    static final String SIGNATURE_SCHEME = "X-Signature = hex(HMAC-SHA256(secret, METHOD|PATH|X-Timestamp|X-Nonce|"
            + "hex(SHA-256(body)))); X-Timestamp = epoch seconds; headers X-Device-Id, X-Timestamp, X-Nonce, X-Signature";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccessDeviceRepository devices;
    private final DeviceCommandRepository commands;
    private final BranchService branchService;
    private final AccessPolicy access;
    private final AccessKeys keys;
    private final AccessProperties props;
    private final AuditService audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<DeviceResponse> list(Long branchId) {
        BranchScope scope = access.scope(branchId);
        Instant now = Instant.now(clock);
        return devices.findScoped(scope.all(), scope.ids()).stream().map(d -> toResponse(d, now)).toList();
    }

    @Transactional(readOnly = true)
    public DeviceResponse get(Long id) {
        AccessDevice device = load(id);
        access.assertBranchAccess(device.getBranch().getId());
        return toResponse(device, Instant.now(clock));
    }

    @Transactional
    public DeviceSecretResponse create(DeviceRequest request) {
        access.assertBranchAccess(request.branchId());
        GymBranch branch = branchService.load(request.branchId());
        Instant now = Instant.now(clock);
        AccessDevice device = new AccessDevice(newDeviceUid(), branch, request.name().trim(), request.type(),
                request.direction(), now);
        device.describe(request.name().trim(), request.type(), request.direction(), request.vendor(), request.model(),
                request.serialNumber(), request.firmwareVersion());
        devices.saveAndFlush(device);
        audit.record(AuditEvent.of("ACCESS_DEVICE", device.getId(), "CREATED").branch(branch.getId())
                .with("deviceUid", device.getDeviceUid()).with("type", device.getDeviceType())
                .with("direction", device.getDirection()));
        return withSecret(device, now);
    }

    @Transactional
    public DeviceResponse update(Long id, DeviceUpdateRequest request) {
        AccessDevice device = load(id);
        access.assertBranchAccess(device.getBranch().getId());
        device.describe(request.name().trim(), request.type(), request.direction(), request.vendor(), request.model(),
                request.serialNumber(), request.firmwareVersion());
        devices.flush();
        audit.record(AuditEvent.of("ACCESS_DEVICE", id, "UPDATED").branch(device.getBranch().getId()));
        return toResponse(device, Instant.now(clock));
    }

    /** Issues a new secret (old one stops working immediately). The new secret is returned once. */
    @Transactional
    public DeviceSecretResponse rotateSecret(Long id) {
        AccessDevice device = devices.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Device", id));
        access.assertBranchAccess(device.getBranch().getId());
        Instant now = Instant.now(clock);
        device.rotateSecret(now);
        audit.record(AuditEvent.of("ACCESS_DEVICE", id, "SECRET_ROTATED").branch(device.getBranch().getId())
                .with("keyVersion", device.getKeyVersion()));
        return withSecret(device, now);
    }

    @Transactional
    public DeviceResponse changeStatus(Long id, DeviceStatus status) {
        AccessDevice device = load(id);
        access.assertBranchAccess(device.getBranch().getId());
        DeviceStatus previous = device.getStatus();
        device.changeStatus(status);
        audit.record(AuditEvent.of("ACCESS_DEVICE", id, "STATUS_CHANGED").branch(device.getBranch().getId())
                .with("from", previous).with("to", status));
        return toResponse(device, Instant.now(clock));
    }

    @Transactional(readOnly = true)
    public List<CommandView> recentCommands(Long id, int limit) {
        AccessDevice device = load(id);
        access.assertBranchAccess(device.getBranch().getId());
        return commands.findRecent(id, PageRequest.of(0, Math.min(Math.max(limit, 1), 100))).stream()
                .map(CommandView::from).toList();
    }

    AccessDevice load(Long id) {
        return devices.findById(id).orElseThrow(() -> new ResourceNotFoundException("Device", id));
    }

    DeviceResponse toResponse(AccessDevice device, Instant now) {
        return DeviceResponse.from(device, device.isOnline(now, props.heartbeatTimeout()));
    }

    private DeviceSecretResponse withSecret(AccessDevice device, Instant now) {
        return new DeviceSecretResponse(toResponse(device, now), keys.deviceSecret(device.getDeviceUid(),
                device.getKeyVersion()), SIGNATURE_SCHEME);
    }

    /** Public, unguessable device identifier (sent as X-Device-Id). */
    static String newDeviceUid() {
        byte[] bytes = new byte[10];
        RANDOM.nextBytes(bytes);
        return "gd_" + HexFormat.of().formatHex(bytes);
    }
}
