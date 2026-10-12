package com.gymops.settings;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.exception.ApiException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optional modules the owner can switch on or off at runtime (stored in the database, so every instance agrees):
 * <ul>
 *   <li>{@code gate-access}: the whole gate / turnstile system (devices, cards, QR pass, biometrics);</li>
 *   <li>{@code biometrics}: fingerprint / face enrolment and scans (cards and the QR pass keep working).</li>
 * </ul>
 * Defaults come from configuration ({@code app.access.enabled}, {@code app.access.biometrics-enabled}).
 */
@Service
@RequiredArgsConstructor
public class FeatureService {

    static final String GATE_ACCESS = "feature.gate-access";
    static final String BIOMETRICS = "feature.biometrics";

    private final AppSettingRepository repository;
    private final AuditService audit;
    private final Clock clock;

    @Value("${app.access.enabled:true}")
    private boolean gateAccessDefault;

    @Value("${app.access.biometrics-enabled:true}")
    private boolean biometricsDefault;

    public record Features(boolean gateAccess, boolean biometrics) {
    }

    /** A null field leaves that switch unchanged. */
    public record FeatureUpdate(Boolean gateAccess, Boolean biometrics) {
    }

    @Transactional(readOnly = true)
    public Features features() {
        boolean gates = flag(GATE_ACCESS, gateAccessDefault);
        return new Features(gates, gates && flag(BIOMETRICS, biometricsDefault));
    }

    @Transactional(readOnly = true)
    public boolean gatesEnabled() {
        return flag(GATE_ACCESS, gateAccessDefault);
    }

    @Transactional(readOnly = true)
    public boolean biometricsEnabled() {
        return gatesEnabled() && flag(BIOMETRICS, biometricsDefault);
    }

    public void requireGates() {
        if (!gatesEnabled()) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED, "Gate access control is switched off (Settings)");
        }
    }

    public void requireBiometrics() {
        requireGates();
        if (!biometricsEnabled()) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED,
                    "Fingerprint / face access is switched off (Settings); use a card or the QR pass");
        }
    }

    @Transactional
    public Features update(FeatureUpdate request) {
        Instant now = Instant.now(clock);
        if (request.gateAccess() != null) {
            set(GATE_ACCESS, request.gateAccess(), now);
        }
        if (request.biometrics() != null) {
            set(BIOMETRICS, request.biometrics(), now);
        }
        return features();
    }

    private void set(String key, boolean value, Instant now) {
        AppSetting setting = repository.findById(key).orElseGet(() -> new AppSetting(key));
        String before = setting.getValue();
        setting.set(String.valueOf(value), CurrentUser.idOrNull(), now);
        repository.save(setting);
        audit.record(AuditEvent.of("SETTING", 0L, "FEATURE_CHANGED").with("key", key).with("from", before)
                .with("to", value));
    }

    private boolean flag(String key, boolean fallback) {
        return repository.findById(key).map(s -> Boolean.parseBoolean(s.getValue())).orElse(fallback);
    }
}
