package com.gymops.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gymops.access.GateAuthenticator.DeviceContext;
import com.gymops.access.dto.AccessDtos.AlarmReport;
import com.gymops.access.dto.AccessDtos.CommandAck;
import com.gymops.access.dto.AccessDtos.ExitButtonRequest;
import com.gymops.access.dto.AccessDtos.GateBatchResult;
import com.gymops.access.dto.AccessDtos.GateDecision;
import com.gymops.access.dto.AccessDtos.HeartbeatRequest;
import com.gymops.access.dto.AccessDtos.ScanBatchRequest;
import com.gymops.access.dto.AccessDtos.ScanRequest;
import com.gymops.common.exception.ApiException;
import com.gymops.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device API for gates (no JWT; {@code permitAll} in SecurityConfig). Every request is authenticated here with the
 * device's HMAC signature, timestamp and nonce ({@link GateAuthenticator}); the raw body bytes are what is signed, so
 * they are read as {@code byte[]} and parsed afterwards. Every response is signed back with the same secret.
 */
@Tag(name = "Access control: device API (HMAC-signed)")
@RestController
@RequestMapping("/api/v1/access/gate")
@RequiredArgsConstructor
public class GateController {

    private final GateAuthenticator authenticator;
    private final GateDecisionService decisions;
    private final DeviceGateService gate;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @Operation(summary = "Heartbeat (device health; clears an offline flag)")
    @PostMapping("/heartbeat")
    public ResponseEntity<byte[]> heartbeat(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        DeviceContext ctx = authenticator.authenticate(request, body);
        HeartbeatRequest heartbeat = body == null || body.length == 0 ? null : parse(body, HeartbeatRequest.class);
        return signed(ctx, gate.heartbeat(ctx, heartbeat), HttpStatus.OK);
    }

    @Operation(summary = "A scan at the gate: returns GRANTED / DENIED with a reason code (idempotent per deviceEventId)")
    @PostMapping("/events")
    public ResponseEntity<byte[]> event(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        DeviceContext ctx = authenticator.authenticate(request, body);
        ScanRequest scan = parse(body, ScanRequest.class);
        return signed(ctx, processOne(ctx, scan), HttpStatus.OK);
    }

    @Operation(summary = "Exit button / free exit (no credential): always opens; logged as an anonymous exit")
    @PostMapping("/exit-button")
    public ResponseEntity<byte[]> exitButton(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        DeviceContext ctx = authenticator.authenticate(request, body);
        ExitButtonRequest press = parse(body, ExitButtonRequest.class);
        GateDecision decision;
        try {
            decision = decisions.exitButton(ctx, press.deviceEventId(), press.occurredAt());
        } catch (DataIntegrityViolationException e) {
            decision = decisions.findExisting(ctx.deviceId(), press.deviceEventId()).orElseThrow(() -> e);
        }
        return signed(ctx, decision, HttpStatus.OK);
    }

    @Operation(summary = "Upload events buffered while offline (processed in order, each idempotent)")
    @PostMapping("/events/batch")
    public ResponseEntity<byte[]> batch(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        DeviceContext ctx = authenticator.authenticate(request, body);
        ScanBatchRequest batch = parse(body, ScanBatchRequest.class);
        List<GateDecision> results = new ArrayList<>();
        for (ScanRequest scan : batch.events()) {
            results.add(processOne(ctx, scan));
        }
        return signed(ctx, new GateBatchResult(results.size(), results), HttpStatus.OK);
    }

    @Operation(summary = "Pending commands for this device (enrol / delete template); re-delivered until acknowledged")
    @GetMapping("/commands")
    public ResponseEntity<byte[]> commands(HttpServletRequest request) {
        DeviceContext ctx = authenticator.authenticate(request, new byte[0]);
        return signed(ctx, gate.pollCommands(ctx), HttpStatus.OK);
    }

    @Operation(summary = "Acknowledge a command (ENROLL_TEMPLATE success must include the device template reference)")
    @PostMapping("/commands/{id}/ack")
    public ResponseEntity<byte[]> ack(HttpServletRequest request, @PathVariable Long id,
                                      @RequestBody(required = false) byte[] body) {
        DeviceContext ctx = authenticator.authenticate(request, body);
        return signed(ctx, gate.acknowledge(ctx, id, parse(body, CommandAck.class)), HttpStatus.OK);
    }

    @Operation(summary = "Report an alarm (door forced / held open / tailgating / tamper); managers are notified")
    @PostMapping("/alarms")
    public ResponseEntity<byte[]> alarm(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        DeviceContext ctx = authenticator.authenticate(request, body);
        return signed(ctx, gate.reportAlarm(ctx, parse(body, AlarmReport.class)), HttpStatus.OK);
    }

    @Operation(summary = "Signed offline allowlist for the next N hours (X-Signature over the exact response bytes)")
    @GetMapping("/allowlist")
    public ResponseEntity<byte[]> allowlist(HttpServletRequest request) {
        DeviceContext ctx = authenticator.authenticate(request, new byte[0]);
        return signed(ctx, gate.allowlist(ctx), HttpStatus.OK);
    }

    private GateDecision processOne(DeviceContext ctx, ScanRequest scan) {
        try {
            return decisions.process(ctx, scan);
        } catch (DataIntegrityViolationException e) {
            // A concurrent duplicate of the same deviceEventId won the insert: answer with the stored decision.
            return decisions.findExisting(ctx.deviceId(), scan.deviceEventId()).orElseThrow(() -> e);
        }
    }

    private <T> T parse(byte[] body, Class<T> type) {
        if (body == null || body.length == 0) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "Request body is required");
        }
        T value;
        try {
            value = objectMapper.readValue(body, type);
        } catch (java.io.IOException e) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "Malformed JSON body");
        }
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return value;
    }

    private ResponseEntity<byte[]> signed(DeviceContext ctx, Object payload, HttpStatus status) {
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise gate response", e);
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(GateSignature.HEADER_SIGNATURE, GateSignature.signResponse(ctx.secret(), ctx.nonce(), bytes))
                .body(bytes);
    }
}
