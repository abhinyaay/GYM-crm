package com.gymops.access;

import com.gymops.common.exception.ApiException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.web.ClientIp;
import com.gymops.settings.FeatureService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Authenticates gate devices on {@code /api/v1/access/gate/**} (no JWT there): HMAC request signature with the
 * device's derived secret, clock-skew window and single-use nonce. Order of checks avoids being a free oracle:
 * malformed headers / stale timestamp are rejected before any lookup, the nonce is burned only after the signature
 * verified (so an attacker cannot exhaust a device's nonces), and unknown devices get the same 401 as bad signatures.
 */
@Slf4j
@Component
public class GateAuthenticator {

    /** The authenticated device for one request. */
    public record DeviceContext(Long deviceId, String deviceUid, Long branchId, String secret, String nonce, String ip) {
    }

    private final AccessDeviceRepository devices;
    private final AccessKeys keys;
    private final AccessProperties props;
    private final ReplayGuard replayGuard;
    private final TransactionTemplate tx;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final FeatureService features;

    public GateAuthenticator(AccessDeviceRepository devices, AccessKeys keys, AccessProperties props, ReplayGuard replayGuard,
                             PlatformTransactionManager transactionManager, MeterRegistry meterRegistry, Clock clock,
                             FeatureService features) {
        this.features = features;
        this.devices = devices;
        this.keys = keys;
        this.props = props;
        this.replayGuard = replayGuard;
        this.tx = new TransactionTemplate(transactionManager);
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public DeviceContext authenticate(HttpServletRequest request, byte[] body) {
        if (!props.enabled() || !features.gatesEnabled()) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED, "Gate access control is switched off by the gym");
        }
        String deviceUid = request.getHeader(GateSignature.HEADER_DEVICE);
        String timestamp = request.getHeader(GateSignature.HEADER_TIMESTAMP);
        String nonce = request.getHeader(GateSignature.HEADER_NONCE);
        String signature = request.getHeader(GateSignature.HEADER_SIGNATURE);
        if (deviceUid == null || deviceUid.length() > 40 || timestamp == null || nonce == null || signature == null) {
            throw reject("missing", "Missing device authentication headers (X-Device-Id, X-Timestamp, X-Nonce, X-Signature)");
        }
        if (!GateSignature.validNonce(nonce) || !GateSignature.wellFormedSignature(signature)) {
            throw reject("malformed", "Malformed X-Nonce or X-Signature");
        }
        Instant now = Instant.now(clock);
        Instant ts = GateSignature.parseTimestamp(timestamp);
        if (!GateSignature.withinSkew(ts, now, props.clockSkew())) {
            throw reject("skew", "X-Timestamp is outside the allowed clock skew of " + props.clockSkew().toSeconds()
                    + "s (server time " + now.getEpochSecond() + ")");
        }
        AccessDevice device = devices.findByDeviceUid(deviceUid).orElse(null);
        if (device == null) {
            throw reject("unknown_device", "Device authentication failed");
        }
        String secret = keys.deviceSecret(device.getDeviceUid(), device.getKeyVersion());
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        if (!GateSignature.verify(secret, request.getMethod(), path, timestamp, nonce, body, signature)) {
            throw reject("bad_signature", "Device authentication failed");
        }
        if (!device.isActive()) {
            meterRegistry.counter("gymops.access.auth.rejected", "reason", "disabled").increment();
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Device is disabled");
        }
        Boolean fresh = tx.execute(status -> replayGuard.claim("nonce:" + device.getId(), nonce, now.plus(props.nonceTtl())));
        if (!Boolean.TRUE.equals(fresh)) {
            throw reject("replay", "Replayed request (nonce already used)");
        }
        return new DeviceContext(device.getId(), device.getDeviceUid(), device.getBranch().getId(), secret, nonce,
                ClientIp.of(request));
    }

    private ApiException reject(String reason, String message) {
        meterRegistry.counter("gymops.access.auth.rejected", "reason", reason).increment();
        log.warn("gate_auth_rejected reason={}", reason);
        return new ApiException(ErrorCode.UNAUTHORIZED, message);
    }
}
