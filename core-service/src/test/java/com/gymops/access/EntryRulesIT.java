package com.gymops.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gymops.support.Api;
import com.gymops.support.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Owner-configurable entry rules: free (push-button) exit vs scan-to-exit, and the plan's daily entry limit. */
class EntryRulesIT extends IntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired AccessJobs jobs;

    private String admin;
    private String manager;
    private String desk;
    private long branchId;

    private final class Gate {
        final String uid;
        final String secret;

        Gate(JsonNode created) {
            uid = created.at("/device/deviceUid").asText();
            secret = created.get("deviceSecret").asText();
        }

        Api.Resp send(String path, Object body) {
            String json;
            try {
                json = JSON.writeValueAsString(body);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            String ts = String.valueOf(clock.instant().getEpochSecond());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String canonical = "POST|" + path + "|" + ts + "|" + nonce + "|" + AccessControlIT.sha256(json);
            Api.Resp r = api.send("POST", path, null, new Api.Raw(json), Map.of("X-Device-Id", uid, "X-Timestamp", ts,
                    "X-Nonce", nonce, "X-Signature", AccessControlIT.hmac(secret, canonical)));
            assertThat(r.status()).as(r.toString()).isEqualTo(200);
            return r;
        }

        Api.Resp scan(String cardUid) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("deviceEventId", "ev-" + UUID.randomUUID());
            body.put("credentialType", "RFID_CARD");
            body.put("credentialValue", cardUid);
            body.put("direction", "ENTRY");
            return send("/api/v1/access/gate/events", body);
        }
    }

    @BeforeEach
    void setUp() {
        admin = admin();
        branchId = createBranch(admin);
        manager = login(createStaff(admin, "MANAGER", branchId), STAFF_PASSWORD);
        desk = login(createStaff(admin, "RECEPTIONIST", branchId), STAFF_PASSWORD);
    }

    private Gate device(String direction) {
        Api.Resp r = api.post("/api/v1/access/devices", manager, Map.of("branchId", branchId, "name", unique("Gate "),
                "type", "TURNSTILE", "direction", direction, "vendor", "Simulated"));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return new Gate(r.body());
    }

    private long member(long planId) {
        long memberId = createMember(desk, branchId);
        subscribeAndPay(desk, memberId, planId);
        return memberId;
    }

    private String card(long memberId) {
        String uid = String.format("CA%08X", memberId);
        Api.Resp r = api.post("/api/v1/access/members/" + memberId + "/credentials/card", desk, Map.of("cardUid", uid));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return uid;
    }

    private long openVisits(long memberId) {
        return jdbc.queryForObject("select count(*) from attendance where member_id = ? and check_out_time is null",
                Long.class, memberId);
    }

    @Test
    void freeExitBranchVerifiesOnlyEntryAndClosesVisitsByItself() {
        Gate entry = device("ENTRY");
        Gate exit = device("EXIT");
        long memberId = member(createPlan(admin, "MONTHLY", "1500.00", "PRORATED", 0, Map.of()));
        String uid = card(memberId);

        JsonNode settings = api.get("/api/v1/access/branches/settings", desk).body();
        assertThat(settings.findValuesAsText("exitMode")).contains("BUTTON");
        assertThat(entry.send("/api/v1/access/gate/heartbeat", Map.of()).body().get("exitMode").asText()).isEqualTo("BUTTON");

        assertThat(entry.scan(uid).body().get("decision").asText()).isEqualTo("GRANTED");
        // nobody scans out at a free-exit branch: the next entry simply ends the previous visit
        Api.Resp again = entry.scan(uid);
        assertThat(again.body().get("decision").asText()).isEqualTo("GRANTED");
        assertThat(again.body().get("antiPassbackViolation").asBoolean()).isFalse();
        assertThat(openVisits(memberId)).isEqualTo(1);

        Api.Resp button = exit.send("/api/v1/access/gate/exit-button", Map.of("deviceEventId", "btn-" + UUID.randomUUID()));
        assertThat(button.body().get("decision").asText()).isEqualTo("GRANTED");
        assertThat(button.body().get("openGate").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from access_events where credential_type = 'EXIT_BUTTON' "
                + "and branch_id = ? and member_id is null", Long.class, branchId)).isEqualTo(1);

        clock.advance(Duration.ofHours(3));
        jobs.closeButtonExitVisits();
        assertThat(openVisits(memberId)).isZero();
        Instant[] times = jdbc.queryForObject("select check_in_time, check_out_time from attendance where member_id = ? "
                + "order by id desc limit 1", (rs, i) -> new Instant[] {rs.getTimestamp(1).toInstant(),
                rs.getTimestamp(2).toInstant()}, memberId);
        assertThat(Duration.between(times[0], times[1])).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void scanToExitBranchEnforcesAntiPassbackAndOnlyManagersChangeTheMode() {
        assertThat(api.send("PUT", "/api/v1/access/branches/" + branchId + "/settings", desk, Map.of("exitMode", "VERIFIED"),
                Map.of()).status()).isEqualTo(403);
        Api.Resp changed = api.send("PUT", "/api/v1/access/branches/" + branchId + "/settings", manager,
                Map.of("exitMode", "VERIFIED"), Map.of());
        assertThat(changed.body().get("exitMode").asText()).isEqualTo("VERIFIED");
        assertThat(changed.body().get("defaulted").asBoolean()).isFalse();

        Gate gate = device("BOTH");
        long memberId = member(createPlan(admin, "MONTHLY", "1500.00", "PRORATED", 0, Map.of()));
        String uid = card(memberId);
        assertThat(gate.scan(uid).body().get("decision").asText()).isEqualTo("GRANTED");
        assertThat(gate.scan(uid).body().get("reasonCode").asText()).isEqualTo("ANTI_PASSBACK");

        // the exit button still opens (nobody may be trapped) but is logged as an unverified exit
        Api.Resp button = gate.send("/api/v1/access/gate/exit-button", Map.of("deviceEventId", "btn-" + UUID.randomUUID()));
        assertThat(button.body().get("decision").asText()).isEqualTo("GRANTED");
        assertThat(button.body().get("antiPassbackViolation").asBoolean()).isTrue();
    }

    @Test
    void dailyEntryLimitAppliesAtTheDeskAndAtTheGate() {
        long planId = createPlan(admin, "MONTHLY", "1500.00", "PRORATED", 0, Map.of("maxEntriesPerDay", 1));
        assertThat(api.get("/api/v1/plans/" + planId, desk).body().at("/access/maxEntriesPerDay").asInt()).isEqualTo(1);
        Gate gate = device("ENTRY");
        long memberId = member(planId);
        String uid = card(memberId);
        Map<String, Object> checkIn = Map.of("memberId", memberId, "branchId", branchId);

        assertThat(api.post("/api/v1/attendance/check-in", desk, checkIn).status()).isEqualTo(201);
        assertThat(api.post("/api/v1/attendance/check-out", desk, Map.of("memberId", memberId)).status()).isEqualTo(200);
        Api.Resp second = api.post("/api/v1/attendance/check-in", desk, checkIn);
        assertThat(second.status()).isEqualTo(422);
        assertThat(second.body().get("message").asText()).contains("Daily entry limit of 1 entry");

        Api.Resp denied = gate.scan(uid);
        assertThat(denied.body().get("decision").asText()).isEqualTo("DENIED");
        assertThat(denied.body().get("reasonCode").asText()).isEqualTo("DAILY_ENTRY_LIMIT");

        clock.advance(Duration.ofDays(1));
        assertThat(gate.scan(uid).body().get("decision").asText()).as("a new day resets the limit").isEqualTo("GRANTED");
    }

    @Test
    void gatesAndBiometricsAreOptionalModulesTheOwnerCanSwitchOff() {
        Gate gate = device("ENTRY");
        long memberId = member(createPlan(admin, "MONTHLY", "1500.00", "PRORATED", 0, Map.of()));
        String uid = card(memberId);
        String version = api.get("/api/v1/access/consent/notice", desk).body().get("version").asText();
        assertThat(api.send("PUT", "/api/v1/settings/features", manager, Map.of("biometrics", false), Map.of()).status())
                .as("only admins switch modules").isEqualTo(403);
        try {
            Api.Resp off = api.send("PUT", "/api/v1/settings/features", admin, Map.of("biometrics", false), Map.of());
            assertThat(off.body().get("gateAccess").asBoolean()).isTrue();
            assertThat(off.body().get("biometrics").asBoolean()).isFalse();
            Api.Resp consent = api.post("/api/v1/access/members/" + memberId + "/consent", desk,
                    Map.of("noticeVersion", version, "accepted", true));
            assertThat(consent.status()).isEqualTo(403);
            assertThat(consent.body().get("code").asText()).isEqualTo("FEATURE_DISABLED");
            assertThat(gate.scan(uid).body().get("decision").asText()).as("cards keep working").isEqualTo("GRANTED");

            api.send("PUT", "/api/v1/settings/features", admin, Map.of("gateAccess", false), Map.of());
            assertThat(api.get("/api/v1/settings/features", desk).body().get("gateAccess").asBoolean()).isFalse();
            assertThat(api.get("/api/v1/access/devices", desk).status()).isEqualTo(403);
            Map<String, Object> body = Map.of("deviceEventId", "hb-" + UUID.randomUUID());
            String json = "{\"deviceEventId\":\"" + body.get("deviceEventId") + "\"}";
            String ts = String.valueOf(clock.instant().getEpochSecond());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            Api.Resp blocked = api.send("POST", "/api/v1/access/gate/exit-button", null, new Api.Raw(json),
                    Map.of("X-Device-Id", gate.uid, "X-Timestamp", ts, "X-Nonce", nonce, "X-Signature",
                            AccessControlIT.hmac(gate.secret, "POST|/api/v1/access/gate/exit-button|" + ts + "|" + nonce + "|"
                                    + AccessControlIT.sha256(json))));
            assertThat(blocked.status()).isEqualTo(403);
            assertThat(blocked.body().get("code").asText()).isEqualTo("FEATURE_DISABLED");
        } finally {
            api.send("PUT", "/api/v1/settings/features", admin, Map.of("gateAccess", true, "biometrics", true), Map.of());
        }
    }
}
