package com.gymops.access;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo gates on top of the core demo data: an entry and an exit turnstile per branch (vendor "Simulated", never
 * heartbeated, so they show as "not connected" until the gate simulator or {@code scripts/gate_simulator.py} talks to
 * them; rotate a device's secret in Access control to get one), RFID cards for most active members, recorded consent +
 * fingerprint template references for some, and the last three days of visits of carded members re-labelled as gate
 * visits with matching ENTRY / EXIT events plus a sprinkling of denials.
 *
 * <p>Demo card UIDs are deterministic: {@code "D3" + 6-hex-digit member id} (e.g. member 42 → D300002A), so the gate
 * simulator can "tap" a demo member's card. Demo template references are {@code FP-DEMO-<memberId>}.
 * Runs only with {@code app.bootstrap.demo-data=true} and only while the access tables are empty.
 */
@Slf4j
@Order(6)
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.bootstrap.demo-data", havingValue = "true")
public class AccessDemoSeeder implements ApplicationRunner {

    private static final String[] DENIALS = {"NO_ACTIVE_MEMBERSHIP", "OUTSIDE_PLAN_HOURS", "UNKNOWN_CREDENTIAL",
        "ANTI_PASSBACK", "CREDENTIAL_REVOKED", "AT_CAPACITY"};

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Random random = new Random(99);

    /** Deterministic demo card UID for a member (also computed by the web gate simulator). */
    public static String demoCardUid(long memberId) {
        return "D3" + String.format(Locale.ROOT, "%06X", memberId);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long devices = jdbc.queryForObject("select count(*) from access_devices", Long.class);
        Long credentials = jdbc.queryForObject("select count(*) from access_credentials", Long.class);
        List<Map<String, Object>> branches = jdbc.queryForList(
                "select id, code, name from gym_branches where deleted_at is null order by id");
        if (devices == null || devices > 0 || credentials == null || credentials > 0 || branches.isEmpty()) {
            log.info("Access demo seeding skipped (access data present or no branches)");
            return;
        }
        Instant now = Instant.now(clock);
        int cards = 0;
        int fingerprints = 0;
        int events = 0;
        for (Map<String, Object> branch : branches) {
            long branchId = ((Number) branch.get("id")).longValue();
            String code = (String) branch.get("code");
            long entryDevice = device(branchId, code, "Main entrance turnstile", "ENTRY", now);
            long exitDevice = device(branchId, code, "Exit turnstile", "EXIT", now);
            Long receptionist = jdbc.query("""
                    select u.id from users u join staff_branch_assignments a on a.user_id = u.id
                    join user_roles ur on ur.user_id = u.id join roles r on r.id = ur.role_id
                    where a.branch_id = ? and r.name = 'RECEPTIONIST' order by u.id limit 1
                    """, rs -> rs.next() ? rs.getLong(1) : null, branchId);
            List<Map<String, Object>> members = jdbc.queryForList("""
                    select id, member_code from members
                    where home_branch_id = ? and status = 'ACTIVE' and deleted_at is null order by id
                    """, branchId);
            List<Long> carded = new ArrayList<>();
            for (Map<String, Object> m : members) {
                long memberId = ((Number) m.get("id")).longValue();
                if (random.nextDouble() < 0.7) {
                    String uid = demoCardUid(memberId);
                    jdbc.update("""
                            insert into access_credentials (member_id, credential_type, status, credential_hash, display_hint,
                                label, activated_at, created_at, updated_at, created_by, updated_by)
                            values (?, 'RFID_CARD', 'ACTIVE', ?, ?, 'Demo card', ?, ?, ?, ?, ?)
                            """, memberId, CredentialHashes.cardHash(uid), CredentialHashes.hint(CredentialType.RFID_CARD, uid),
                            ts(now.minus(Duration.ofDays(30))), ts(now.minus(Duration.ofDays(30))), ts(now), receptionist,
                            receptionist);
                    carded.add(memberId);
                    cards++;
                }
                if (random.nextDouble() < 0.2) {
                    Instant granted = now.minus(Duration.ofDays(20 + random.nextInt(40)));
                    Long consentId = jdbc.queryForObject("""
                            insert into biometric_consents (member_id, purpose, consent_text_version, consent_text_sha256,
                                capture_method, granted_at, granted_by)
                            values (?, 'GYM_ENTRY_VERIFICATION', ?, ?, 'STAFF_ASSISTED', ?, ?) returning id
                            """, Long.class, memberId, BiometricConsentNotice.VERSION, BiometricConsentNotice.SHA256,
                            ts(granted), receptionist);
                    String ref = "FP-DEMO-" + memberId;
                    jdbc.update("""
                            insert into access_credentials (member_id, credential_type, status, credential_hash, template_ref,
                                display_hint, label, consent_id, enrolled_device_id, activated_at, created_at, updated_at,
                                created_by, updated_by)
                            values (?, 'FINGERPRINT', 'ACTIVE', ?, ?, ?, 'Right index', ?, ?, ?, ?, ?, ?, ?)
                            """, memberId, CredentialHashes.templateHash(CredentialType.FINGERPRINT, ref), ref,
                            CredentialHashes.hint(CredentialType.FINGERPRINT, ref), consentId, entryDevice, ts(granted),
                            ts(granted), ts(granted), receptionist, receptionist);
                    jdbc.update("""
                            insert into access_device_commands (device_id, command_type, credential_id, credential_type,
                                template_ref, member_ref, status, delivery_count, created_at, delivered_at, completed_at,
                                result_message)
                            select ?, 'ENROLL_TEMPLATE', id, 'FINGERPRINT', ?, ?, 'SUCCEEDED', 1, ?, ?, ?, 'demo enrolment'
                            from access_credentials where template_ref = ?
                            """, entryDevice, ref, m.get("member_code"), ts(granted), ts(granted), ts(granted), ref);
                    fingerprints++;
                }
            }
            events += gateHistory(branchId, entryDevice, exitDevice, carded, now);
        }
        log.info("Access demo data seeded: {} devices, {} cards, {} fingerprint references, {} access events",
                branches.size() * 2, cards, fingerprints, events);
    }

    private long device(long branchId, String branchCode, String name, String direction, Instant now) {
        return jdbc.queryForObject("""
                insert into access_devices (device_uid, branch_id, name, device_type, direction, vendor, model, serial_number,
                    firmware_version, status, key_version, secret_issued_at, created_at, updated_at)
                values (?, ?, ?, 'TURNSTILE', ?, 'Simulated', 'GymOps gate simulator', ?, '1.0.0', 'ACTIVE', 1, ?, ?, ?)
                returning id
                """, Long.class, DeviceService.newDeviceUid(), branchId, name, direction,
                "SIM-" + branchCode + "-" + direction, ts(now), ts(now), ts(now));
    }

    /** Re-labels the last 3 days of carded members' visits as gate visits and logs matching events and some denials. */
    private int gateHistory(long branchId, long entryDevice, long exitDevice, List<Long> carded, Instant now) {
        if (carded.isEmpty()) {
            return 0;
        }
        List<Map<String, Object>> visits = jdbc.queryForList("""
                select id, member_id, check_in_time, check_out_time, auto_checked_out from attendance
                where branch_id = ? and check_in_time >= ? and member_id = any (?)
                order by check_in_time
                """, branchId, ts(now.minus(Duration.ofDays(3))), carded.toArray(new Long[0]));
        Map<Long, Long> cardByMember = new java.util.HashMap<>();
        jdbc.query("select member_id, id from access_credentials where credential_type = 'RFID_CARD' and member_id = any (?)",
                rs -> {
                    cardByMember.put(rs.getLong(1), rs.getLong(2));
                }, (Object) carded.toArray(new Long[0]));
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> v : visits) {
            long visitId = ((Number) v.get("id")).longValue();
            long memberId = ((Number) v.get("member_id")).longValue();
            String uid = demoCardUid(memberId);
            Long credentialId = cardByMember.get(memberId);
            Timestamp in = (Timestamp) v.get("check_in_time");
            jdbc.update("update attendance set check_in_method = 'GATE', checked_in_by = null where id = ?", visitId);
            rows.add(event(entryDevice, branchId, "demo-in-" + visitId, memberId, credentialId, uid, "ENTRY", "GRANTED", "OK",
                    "Welcome", visitId, in));
            Timestamp out = (Timestamp) v.get("check_out_time");
            if (out != null && !Boolean.TRUE.equals(v.get("auto_checked_out"))) {
                rows.add(event(exitDevice, branchId, "demo-out-" + visitId, memberId, credentialId, uid, "EXIT", "GRANTED", "OK",
                        "Goodbye", visitId, out));
            }
            if (random.nextDouble() < 0.06) {
                String reason = DENIALS[random.nextInt(DENIALS.length)];
                boolean unknown = "UNKNOWN_CREDENTIAL".equals(reason);
                rows.add(event(entryDevice, branchId, "demo-deny-" + visitId, unknown ? null : memberId,
                        unknown ? null : credentialId, unknown ? "0451A9" + Integer.toHexString(random.nextInt(4096)).toUpperCase()
                                : uid, "ENTRY", "DENIED", reason, GateDecisionService.describe(AccessReason.valueOf(reason)),
                        null, Timestamp.from(in.toInstant().minus(Duration.ofMinutes(3 + random.nextInt(50))))));
            }
        }
        jdbc.batchUpdate("""
                insert into access_events (device_id, branch_id, device_event_id, member_id, credential_id, credential_type,
                    credential_hint, direction, decision, reason_code, message, attendance_id, offline,
                    anti_passback_violation, occurred_at, received_at)
                values (?, ?, ?, ?, ?, 'RFID_CARD', ?, ?, ?, ?, ?, ?, false, ?, ?, ?)
                """, rows);
        return rows.size();
    }

    private static Object[] event(long deviceId, long branchId, String eventId, Long memberId, Long credentialId, String uid,
                                  String direction, String decision, String reason, String message, Long attendanceId,
                                  Timestamp at) {
        return new Object[] {deviceId, branchId, eventId, memberId, credentialId,
            CredentialHashes.hint(CredentialType.RFID_CARD, uid), direction, decision, reason, message,
            "DENIED".equals(decision) ? null : attendanceId, "ANTI_PASSBACK".equals(reason), at, at};
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
