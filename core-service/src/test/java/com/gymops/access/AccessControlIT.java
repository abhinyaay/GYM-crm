package com.gymops.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gymops.support.Api;
import com.gymops.support.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end tests of the gate device API (signed requests computed independently of the production signer, as a
 * device would) and of the staff / member access API, against PostgreSQL and Redis containers.
 */
class AccessControlIT extends IntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired AccessJobs jobs;

    private String admin;
    private long branchId;
    private long planId;
    private String manager;
    private String desk;

    /** A simulated gate: signs every request exactly like real firmware would. */
    private final class Gate {
        final String uid;
        final String secret;
        final long id;
        long timestampOffsetSeconds;

        Gate(JsonNode created) {
            this.uid = created.at("/device/deviceUid").asText();
            this.secret = created.get("deviceSecret").asText();
            this.id = created.at("/device/id").asLong();
        }

        Gate(String uid, String secret, long id) {
            this.uid = uid;
            this.secret = secret;
            this.id = id;
        }

        Api.Resp send(String method, String path, Object body) {
            return send(method, path, body, UUID.randomUUID().toString().replace("-", ""));
        }

        Api.Resp send(String method, String path, Object body, String nonce) {
            String json = body == null ? "" : write(body);
            String ts = String.valueOf(clock.instant().getEpochSecond() + timestampOffsetSeconds);
            String canonical = method + "|" + path + "|" + ts + "|" + nonce + "|" + sha256(json);
            Map<String, String> headers = Map.of("X-Device-Id", uid, "X-Timestamp", ts, "X-Nonce", nonce,
                    "X-Signature", hmac(secret, canonical));
            return api.send(method, path, null, body == null ? null : new Api.Raw(json), headers);
        }

        Api.Resp scan(String type, String value, String direction) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("deviceEventId", "ev-" + UUID.randomUUID());
            body.put("credentialType", type);
            body.put("credentialValue", value);
            body.put("direction", direction);
            Api.Resp r = send("POST", "/api/v1/access/gate/events", body);
            assertThat(r.status()).as(r.toString()).isEqualTo(200);
            return r;
        }
    }

    @BeforeEach
    void setUp() {
        admin = admin();
        branchId = createBranch(admin);
        planId = createPlan(admin, "MONTHLY", "1500.00", "PRORATED", 0, Map.of());
        manager = login(createStaff(admin, "MANAGER", branchId), STAFF_PASSWORD);
        desk = login(createStaff(admin, "RECEPTIONIST", branchId), STAFF_PASSWORD);
        // these scenarios exercise scan-to-exit (anti-passback); free-exit branches are covered in EntryRulesIT
        Api.Resp mode = api.send("PUT", "/api/v1/access/branches/" + branchId + "/settings", manager,
                Map.of("exitMode", "VERIFIED"), Map.of());
        assertThat(mode.status()).as(mode.toString()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ helpers

    private Gate device(String direction) {
        Api.Resp r = api.post("/api/v1/access/devices", manager, Map.of("branchId", branchId, "name", unique("Gate "),
                "type", "TURNSTILE", "direction", direction, "vendor", "Simulated"));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        assertThat(r.body().get("deviceSecret").asText()).hasSize(64);
        return new Gate(r.body());
    }

    private long payingMember() {
        long memberId = createMember(desk, branchId);
        subscribeAndPay(desk, memberId, planId);
        return memberId;
    }

    private String card(long memberId) {
        String uid = String.format("AB%06X", memberId) + Integer.toHexString(SEQ_CARD++).toUpperCase();
        Api.Resp r = api.post("/api/v1/access/members/" + memberId + "/credentials/card", desk, Map.of("cardUid", uid));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return uid;
    }

    private static int SEQ_CARD = 0x100;

    private static void assertDecision(Api.Resp r, String decision, String reason) {
        assertThat(r.body().get("decision").asText()).as(r.toString()).isEqualTo(decision);
        assertThat(r.body().get("reasonCode").asText()).as(r.toString()).isEqualTo(reason);
    }

    private long openVisits(long memberId) {
        return jdbc.queryForObject("select count(*) from attendance where member_id = ? and check_out_time is null",
                Long.class, memberId);
    }

    // ------------------------------------------------------------------ scans

    @Test
    void signedCardScanAdmitsThenExitClosesTheVisit() {
        Gate entry = device("ENTRY");
        Gate exit = device("EXIT");
        long memberId = payingMember();
        String uid = card(memberId);

        Api.Resp in = entry.scan("RFID_CARD", uid.toLowerCase(), "ENTRY");
        assertDecision(in, "GRANTED", "OK");
        assertThat(in.body().get("openGate").asBoolean()).isTrue();
        assertThat(in.body().get("memberDisplayName").asText()).isEqualTo("Test M.");
        assertThat(openVisits(memberId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select check_in_method from attendance where member_id = ?", String.class, memberId))
                .isEqualTo("GATE");
        // the response is signed with the device secret, bound to the request nonce
        assertThat(in.header("X-Signature")).hasSize(64);

        Api.Resp out = exit.scan("RFID_CARD", uid, "EXIT");
        assertDecision(out, "GRANTED", "OK");
        assertThat(openVisits(memberId)).isZero();
        // the same visit was closed (one row), and both scans are in the access log
        assertThat(jdbc.queryForObject("select count(*) from access_events where member_id = ?", Long.class, memberId))
                .isEqualTo(2);
        // a device that only handles ENTRY refuses exits
        assertDecision(entry.scan("RFID_CARD", uid, "EXIT"), "DENIED", "DIRECTION_NOT_ALLOWED");
    }

    @Test
    void antiPassbackDeniesSecondEntryAndExitWithoutEntry() {
        Gate gate = device("BOTH");
        long memberId = payingMember();
        String uid = card(memberId);

        assertDecision(gate.scan("RFID_CARD", uid, "EXIT"), "DENIED", "ANTI_PASSBACK");
        assertDecision(gate.scan("RFID_CARD", uid, "ENTRY"), "GRANTED", "OK");
        Api.Resp again = gate.scan("RFID_CARD", uid, "ENTRY");
        assertDecision(again, "DENIED", "ANTI_PASSBACK");
        assertThat(again.body().get("antiPassbackViolation").asBoolean()).isTrue();
        assertThat(openVisits(memberId)).isEqualTo(1);
    }

    @Test
    void deniedCasesUseTheSharedAttendanceRules() {
        Gate gate = device("ENTRY");
        // no membership at all
        long noPlan = createMember(desk, branchId);
        assertDecision(gate.scan("RFID_CARD", card(noPlan), "ENTRY"), "DENIED", "NO_ACTIVE_MEMBERSHIP");
        // unknown card
        assertDecision(gate.scan("RFID_CARD", "DEADBEEF01", "ENTRY"), "DENIED", "UNKNOWN_CREDENTIAL");
        // frozen membership
        long frozen = createMember(desk, branchId);
        long subId = subscribeAndPay(desk, frozen, planId);
        assertThat(api.post("/api/v1/subscriptions/" + subId + "/freeze", desk, Map.of("days", 5)).status()).isEqualTo(200);
        assertDecision(gate.scan("RFID_CARD", card(frozen), "ENTRY"), "DENIED", "FROZEN");
        // revoked card
        long revoked = payingMember();
        String uid = card(revoked);
        long credentialId = jdbc.queryForObject("select id from access_credentials where member_id = ?", Long.class, revoked);
        assertThat(api.post("/api/v1/access/credentials/" + credentialId + "/revoke", desk, Map.of("reason", "lost"))
                .status()).isEqualTo(200);
        assertDecision(gate.scan("RFID_CARD", uid, "ENTRY"), "DENIED", "CREDENTIAL_REVOKED");
        // outside the plan's daily access window
        int hour = clock.instant().atZone(ZoneId.of("Asia/Kolkata")).getHour();
        LocalTime start = hour >= 12 ? LocalTime.of(2, 0) : LocalTime.of(14, 0);
        long offPeakPlan = createPlan(admin, "MONTHLY", "900.00", "PRORATED", 0,
                Map.of("accessStartTime", start.toString(), "accessEndTime", start.plusHours(1).toString()));
        long offPeak = createMember(desk, branchId);
        subscribeAndPay(desk, offPeak, offPeakPlan);
        assertDecision(gate.scan("RFID_CARD", card(offPeak), "ENTRY"), "DENIED", "OUTSIDE_PLAN_HOURS");
        // suspended member account: credentials are suspended with it and restored on reactivation
        long suspended = payingMember();
        String suspendedCard = card(suspended);
        assertThat(api.patch("/api/v1/members/" + suspended + "/status", manager, Map.of("status", "SUSPENDED")).status())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from access_credentials where member_id = ?", String.class, suspended))
                .isEqualTo("SUSPENDED");
        assertDecision(gate.scan("RFID_CARD", suspendedCard, "ENTRY"), "DENIED", "MEMBER_SUSPENDED");
        assertThat(api.patch("/api/v1/members/" + suspended + "/status", manager, Map.of("status", "ACTIVE")).status())
                .isEqualTo(200);
        assertDecision(gate.scan("RFID_CARD", suspendedCard, "ENTRY"), "GRANTED", "OK");
        // every scan, granted or denied, is logged
        assertThat(jdbc.queryForObject("select count(*) from access_events where device_id = ?", Long.class, gate.id))
                .isEqualTo(7);
    }

    @Test
    void sameDeviceEventIdReturnsTheSameDecision() {
        Gate gate = device("ENTRY");
        long memberId = payingMember();
        String uid = card(memberId);
        Map<String, Object> body = Map.of("deviceEventId", "evt-dup-1", "credentialType", "RFID_CARD",
                "credentialValue", uid, "direction", "ENTRY");
        Api.Resp first = gate.send("POST", "/api/v1/access/gate/events", body);
        Api.Resp second = gate.send("POST", "/api/v1/access/gate/events", body);
        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().get("eventId").asLong()).isEqualTo(first.body().get("eventId").asLong());
        assertThat(second.body().get("decision").asText()).isEqualTo("GRANTED");
        assertThat(second.body().get("replayed").asBoolean()).isTrue();
        assertThat(openVisits(memberId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from access_events where device_id = ?", Long.class, gate.id))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ device authentication

    @Test
    void unsignedTamperedSkewedReplayedAndRotatedRequestsAreRejected() {
        Gate gate = device("ENTRY");
        Map<String, Object> heartbeat = Map.of("firmwareVersion", "2.1.0");

        assertThat(api.post("/api/v1/access/gate/heartbeat", null, heartbeat).status()).isEqualTo(401);
        assertThat(gate.send("POST", "/api/v1/access/gate/heartbeat", heartbeat).status()).isEqualTo(200);

        Gate wrongSecret = new Gate(gate.uid, "0".repeat(64), gate.id);
        assertThat(wrongSecret.send("POST", "/api/v1/access/gate/heartbeat", heartbeat).status()).isEqualTo(401);
        Gate unknown = new Gate("gd_unknown_device", gate.secret, gate.id);
        assertThat(unknown.send("POST", "/api/v1/access/gate/heartbeat", heartbeat).status()).isEqualTo(401);

        gate.timestampOffsetSeconds = -400;
        Api.Resp skewed = gate.send("POST", "/api/v1/access/gate/heartbeat", heartbeat);
        assertThat(skewed.status()).isEqualTo(401);
        assertThat(skewed.body().get("message").asText()).contains("clock skew");
        gate.timestampOffsetSeconds = 0;

        String nonce = "nonce" + UUID.randomUUID().toString().replace("-", "");
        assertThat(gate.send("POST", "/api/v1/access/gate/heartbeat", heartbeat, nonce).status()).isEqualTo(200);
        Api.Resp replay = gate.send("POST", "/api/v1/access/gate/heartbeat", heartbeat, nonce);
        assertThat(replay.status()).isEqualTo(401);
        assertThat(replay.body().get("message").asText()).contains("Replayed");

        // body tampering: sign one body, send another
        String ts = String.valueOf(clock.instant().getEpochSecond());
        String n2 = UUID.randomUUID().toString().replace("-", "");
        String signature = hmac(gate.secret, "POST|/api/v1/access/gate/heartbeat|" + ts + "|" + n2 + "|"
                + sha256("{\"firmwareVersion\":\"1\"}"));
        Api.Resp tampered = api.send("POST", "/api/v1/access/gate/heartbeat", null,
                new Api.Raw("{\"firmwareVersion\":\"2\"}"), Map.of("X-Device-Id", gate.uid, "X-Timestamp", ts,
                        "X-Nonce", n2, "X-Signature", signature));
        assertThat(tampered.status()).isEqualTo(401);

        // rotating the secret invalidates the old one at once
        Api.Resp rotated = api.post("/api/v1/access/devices/" + gate.id + "/rotate-secret", manager, null);
        assertThat(rotated.status()).isEqualTo(200);
        assertThat(gate.send("POST", "/api/v1/access/gate/heartbeat", heartbeat).status()).isEqualTo(401);
        Gate fresh = new Gate(rotated.body());
        assertThat(fresh.send("POST", "/api/v1/access/gate/heartbeat", heartbeat).status()).isEqualTo(200);

        // a disabled device is refused even with a valid signature
        assertThat(api.patch("/api/v1/access/devices/" + gate.id + "/status", manager, Map.of("status", "DISABLED"))
                .status()).isEqualTo(200);
        assertThat(fresh.send("POST", "/api/v1/access/gate/heartbeat", heartbeat).status()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ QR pass

    @Test
    void rotatingQrPassAdmitsOncePerWindow() {
        Gate gate = device("BOTH");
        Api.Resp signup = api.post("/api/v1/auth/register", null, Map.of("email", unique("qr") + "@example.com",
                "password", "MemberPassw0rd", "firstName", "Qr", "lastName", "Member", "phone", "+91 9111111111",
                "homeBranchId", branchId));
        assertThat(signup.status()).as(signup.toString()).isEqualTo(201);
        String member = signup.body().get("accessToken").asText();
        long memberId = signup.body().at("/user/memberId").asLong();
        subscribeAndPay(desk, memberId, planId);

        Api.Resp pass = api.get("/api/v1/access/me/pass", member);
        assertThat(pass.status()).as(pass.toString()).isEqualTo(200);
        String token = pass.body().get("token").asText();
        assertThat(token).startsWith("GQ1.");
        assertThat(pass.body().get("windowSeconds").asLong()).isEqualTo(30);
        // a second fetch in the same window gives the same token and writes nothing new
        assertThat(api.get("/api/v1/access/me/pass", member).body().get("token").asText()).isEqualTo(token);

        assertDecision(gate.scan("QR", token, "ENTRY"), "GRANTED", "OK");
        assertDecision(gate.scan("QR", token, "EXIT"), "DENIED", "QR_REUSED");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
        assertDecision(gate.scan("QR", tampered, "EXIT"), "DENIED", "QR_INVALID");
        // staff cannot fetch a member's pass
        assertThat(api.get("/api/v1/access/me/pass", desk).status()).isEqualTo(403);
        // the member sees their own entry log
        Api.Resp log = api.get("/api/v1/access/me/events", member);
        assertThat(log.status()).isEqualTo(200);
        assertThat(log.body().get("totalElements").asLong()).isEqualTo(2);   // the forged token matches no member
    }

    // ------------------------------------------------------------------ consent & biometrics

    @Test
    void biometricEnrolmentNeedsConsentAndWithdrawalRevokesAndQueuesDeletion() {
        Gate gate = device("BOTH");
        Gate other = device("EXIT");
        long memberId = payingMember();
        Map<String, Object> enroll = Map.of("type", "FINGERPRINT", "deviceId", gate.id);

        Api.Resp noConsent = api.post("/api/v1/access/members/" + memberId + "/credentials/biometric", desk, enroll);
        assertThat(noConsent.status()).isEqualTo(422);

        String version = api.get("/api/v1/access/consent/notice", desk).body().get("version").asText();
        Api.Resp notAccepted = api.post("/api/v1/access/members/" + memberId + "/consent", desk,
                Map.of("noticeVersion", version, "accepted", false));
        assertThat(notAccepted.status()).isEqualTo(400);
        Api.Resp consent = api.post("/api/v1/access/members/" + memberId + "/consent", desk,
                Map.of("noticeVersion", version, "accepted", true));
        assertThat(consent.status()).as(consent.toString()).isEqualTo(201);
        assertThat(consent.body().get("captureMethod").asText()).isEqualTo("STAFF_ASSISTED");

        Api.Resp pending = api.post("/api/v1/access/members/" + memberId + "/credentials/biometric", desk, enroll);
        assertThat(pending.status()).as(pending.toString()).isEqualTo(201);
        assertThat(pending.body().get("status").asText()).isEqualTo("PENDING");

        // the device polls, captures the finger and acknowledges with its template reference
        Api.Resp poll = gate.send("GET", "/api/v1/access/gate/commands", null);
        assertThat(poll.status()).isEqualTo(200);
        JsonNode command = poll.body().get("commands").get(0);
        assertThat(command.get("type").asText()).isEqualTo("ENROLL_TEMPLATE");
        String templateRef = "FP-IT-" + memberId;
        Api.Resp ack = gate.send("POST", "/api/v1/access/gate/commands/" + command.get("id").asLong() + "/ack",
                Map.of("success", true, "templateRef", templateRef));
        assertThat(ack.status()).as(ack.toString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from access_credentials where member_id = ? and credential_type = 'FINGERPRINT'",
                String.class, memberId)).isEqualTo("ACTIVE");
        assertDecision(gate.scan("FINGERPRINT", templateRef, "ENTRY"), "GRANTED", "OK");

        Api.Resp withdrawn = api.post("/api/v1/access/members/" + memberId + "/consent/withdraw", desk,
                Map.of("reason", "member request"));
        assertThat(withdrawn.status()).as(withdrawn.toString()).isEqualTo(200);
        assertThat(withdrawn.body().get("credentialsRevoked").asInt()).isEqualTo(1);
        assertThat(withdrawn.body().get("deleteCommandsQueued").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("select status from access_credentials where member_id = ? and credential_type = 'FINGERPRINT'",
                String.class, memberId)).isEqualTo("REVOKED");
        for (Gate g : List.of(gate, other)) {
            JsonNode cmds = g.send("GET", "/api/v1/access/gate/commands", null).body().get("commands");
            assertThat(cmds).anySatisfy(c -> {
                assertThat(c.get("type").asText()).isEqualTo("DELETE_TEMPLATE");
                assertThat(c.get("templateRef").asText()).isEqualTo(templateRef);
            });
        }
        // the member can still leave (exit is never blocked for someone inside), but cannot come back in
        assertDecision(gate.scan("FINGERPRINT", templateRef, "EXIT"), "GRANTED", "OK");
        assertDecision(gate.scan("FINGERPRINT", templateRef, "ENTRY"), "DENIED", "CREDENTIAL_REVOKED");
        assertThat(jdbc.queryForObject("select count(*) from notifications where member_id = ? and type = 'BIOMETRIC_CONSENT_WITHDRAWN'",
                Long.class, memberId)).isPositive();
        JsonNode status = api.get("/api/v1/access/members/" + memberId + "/consent", desk).body();
        assertThat(status.get("active").asBoolean()).isFalse();
        assertThat(status.get("history")).hasSize(1);
    }

    // ------------------------------------------------------------------ offline mode

    @Test
    void offlineAllowlistIsSignedAndBufferedEventsAreReconciled() {
        Gate gate = device("BOTH");
        long memberId = payingMember();
        String uid = card(memberId);
        long noPlan = createMember(desk, branchId);
        String noPlanUid = card(noPlan);

        Api.Resp list = gate.send("GET", "/api/v1/access/gate/allowlist", null);
        assertThat(list.status()).isEqualTo(200);
        String signature = list.header("X-Signature");
        assertThat(signature).isNotBlank();
        String nonce = list.raw().request().headers().firstValue("X-Nonce").orElseThrow();
        assertThat(signature).isEqualTo(hmac(gate.secret, "response|" + nonce + "|" + sha256(list.raw().body())));
        String expectedHash = sha256("RFID_CARD:" + uid);
        assertThat(list.body().get("entries")).anySatisfy(e -> assertThat(e.get("credentialHash").asText()).isEqualTo(expectedHash));
        assertThat(list.body().get("entries")).noneSatisfy(e -> assertThat(e.get("credentialHash").asText())
                .isEqualTo(sha256("RFID_CARD:" + noPlanUid)));

        Instant in = clock.instant().minus(Duration.ofMinutes(50));
        Instant out = clock.instant().minus(Duration.ofMinutes(5));
        Map<String, Object> entry = new HashMap<>(Map.of("deviceEventId", "off-1-" + memberId, "credentialType", "RFID_CARD",
                "credentialValue", uid, "direction", "ENTRY", "occurredAt", in.toString(), "offlineDecision", "GRANTED"));
        Map<String, Object> exit = new HashMap<>(entry);
        exit.putAll(Map.of("deviceEventId", "off-2-" + memberId, "direction", "EXIT", "occurredAt", out.toString()));
        Api.Resp batch = gate.send("POST", "/api/v1/access/gate/events/batch", Map.of("events", List.of(entry, exit)));
        assertThat(batch.status()).as(batch.toString()).isEqualTo(200);
        assertThat(batch.body().get("processed").asInt()).isEqualTo(2);
        Map<String, Object> visit = jdbc.queryForMap(
                "select check_in_time, check_out_time from attendance where member_id = ?", memberId);
        assertThat(((java.sql.Timestamp) visit.get("check_in_time")).toInstant().getEpochSecond()).isEqualTo(in.getEpochSecond());
        assertThat(((java.sql.Timestamp) visit.get("check_out_time")).toInstant().getEpochSecond()).isEqualTo(out.getEpochSecond());
        assertThat(jdbc.queryForObject("select count(*) from access_events where member_id = ? and offline", Long.class, memberId))
                .isEqualTo(2);
        // re-uploading the same batch after a lost response is a no-op
        Api.Resp again = gate.send("POST", "/api/v1/access/gate/events/batch", Map.of("events", List.of(entry, exit)));
        assertThat(again.body().get("results").get(0).get("replayed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from attendance where member_id = ?", Long.class, memberId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ alarms, heartbeats, scoping

    @Test
    void alarmsAndSilentDevicesNotifyBranchManagersOnce() {
        Gate gate = device("ENTRY");
        Api.Resp alarm = gate.send("POST", "/api/v1/access/gate/alarms",
                Map.of("deviceEventId", "alarm-1", "type", "DOOR_FORCED", "details", "door contact open without a grant"));
        assertThat(alarm.status()).as(alarm.toString()).isEqualTo(200);
        assertThat(gate.send("POST", "/api/v1/access/gate/alarms", Map.of("deviceEventId", "alarm-1", "type", "DOOR_FORCED"))
                .body().get("replayed").asBoolean()).isTrue();
        long managerId = api.get("/api/v1/users/me", manager).id();
        assertThat(jdbc.queryForObject("select count(*) from notifications where recipient_user_id = ? and type = 'ACCESS_ALARM'",
                Long.class, managerId)).isEqualTo(1);

        assertThat(gate.send("POST", "/api/v1/access/gate/heartbeat", Map.of("firmwareVersion", "3.0")).status()).isEqualTo(200);
        jdbc.update("update access_devices set last_heartbeat_at = now() - interval '10 minutes' where id = ?", gate.id);
        jobs.monitorHeartbeats();
        jobs.monitorHeartbeats();
        assertThat(jdbc.queryForObject("select count(*) from notifications where recipient_user_id = ? and type = 'ACCESS_DEVICE_OFFLINE'",
                Long.class, managerId)).isEqualTo(1);
        assertThat(api.get("/api/v1/access/devices/" + gate.id, desk).body().get("online").asBoolean()).isFalse();
        // the next heartbeat clears the outage
        gate.send("POST", "/api/v1/access/gate/heartbeat", Map.of());
        assertThat(jdbc.queryForObject("select offline_since is null from access_devices where id = ?", Boolean.class, gate.id))
                .isTrue();
        Api.Resp alarms = api.get("/api/v1/access/alarms?branchId=" + branchId + "&openOnly=true", desk);
        assertThat(alarms.body()).hasSize(2);
        assertThat(api.post("/api/v1/access/alarms/" + alarm.body().get("alarmId").asLong() + "/acknowledge", desk, null)
                .status()).isEqualTo(200);
    }

    @Test
    void staffAreScopedToTheirBranches() {
        Gate gate = device("ENTRY");
        long otherBranch = createBranch(admin);
        String otherManager = login(createStaff(admin, "MANAGER", otherBranch), STAFF_PASSWORD);

        assertThat(api.get("/api/v1/access/devices/" + gate.id, otherManager).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/access/devices/" + gate.id + "/rotate-secret", otherManager, null).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/access/devices", otherManager, Map.of("branchId", branchId, "name", "Sneaky",
                "type", "DOOR", "direction", "BOTH")).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/access/events?branchId=" + branchId, otherManager).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/access/devices", otherManager).body()).noneSatisfy(d ->
                assertThat(d.get("id").asLong()).isEqualTo(gate.id));
        // receptionists can view devices but not manage them
        assertThat(api.get("/api/v1/access/devices/" + gate.id, desk).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/access/devices/" + gate.id + "/rotate-secret", desk, null).status()).isEqualTo(403);
        long memberId = payingMember();
        assertThat(api.post("/api/v1/access/members/" + memberId + "/credentials/card", otherManager,
                Map.of("cardUid", "C0FFEE01")).status()).isEqualTo(403);
        Api.Resp stats = api.get("/api/v1/access/stats?branchId=" + branchId, manager);
        assertThat(stats.status()).as(stats.toString()).isEqualTo(200);
        assertThat(stats.body().get("devices").asLong()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ crypto helpers (independent of production code)

    private static String write(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hmac(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
