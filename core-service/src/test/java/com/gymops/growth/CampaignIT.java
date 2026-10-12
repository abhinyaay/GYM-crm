package com.gymops.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.gymops.growth.campaign.ChurnBand;
import com.gymops.growth.campaign.ChurnRiskSource.ChurnRiskUnavailableException;
import com.gymops.support.Api;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Retention campaigns: preview and run per segment, cooldown de-duplication, personal single-use offer
 * codes, attribution after renewal, churn-risk segment with the analytics service up and down,
 * scheduling, branch scoping and validation.
 */
class CampaignIT extends GrowthITBase {

    @BeforeEach
    void setUp() {
        setUpBranch();
    }

    private Map<String, Object> campaign(String segment, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("name", unique("Campaign "), "branchId", branchId,
                "segment", segment, "titleTemplate", "Hi {firstName}",
                "messageTemplate", "Hi {firstName}, your {planName} ends on {endDate}.", "schedule", "NONE"));
        body.putAll(extra);
        return body;
    }

    private long create(Map<String, Object> body) {
        Api.Resp r = api.post("/api/v1/campaigns", manager, body);
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.id();
    }

    private JsonNode run(long id) {
        Api.Resp r = api.post("/api/v1/campaigns/" + id + "/run", manager, null);
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r.body();
    }

    @Test
    void expiringSegmentPreviewRunCooldownAndAttributionAfterRenewal() {
        long member = deskMember("Meera", unique("meera") + "@example.com", "+91 98000 00001");
        long subId = subscribeAndPay(desk, member, planId);
        long inactive = deskMember("Nikhil", unique("nik") + "@example.com", "+91 98000 00002");   // no membership

        Map<String, Object> body = campaign("EXPIRING_NO_AUTOPAY", Map.of("segmentDays", 40));
        Api.Resp draft = api.post("/api/v1/campaigns/preview", manager, body);
        assertThat(draft.status()).as(draft.toString()).isEqualTo(200);
        assertThat(draft.body().get("audienceSize").asInt()).isEqualTo(1);
        assertThat(draft.body().at("/sample/0/memberId").asLong()).isEqualTo(member);
        assertThat(draft.body().at("/sample/0/body").asText()).startsWith("Hi Meera, your Plan ends on ");

        long id = create(body);
        JsonNode first = run(id);
        assertThat(first.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(first.get("sent").asInt()).isEqualTo(1);
        assertThat(count("select count(*) from campaign_recipients where campaign_id = ? and member_id = ?", id, member))
                .isEqualTo(1);
        assertThat(count("select count(*) from campaign_recipients where member_id = ?", inactive)).isZero();

        // cooldown: running again right away messages nobody
        JsonNode preview = api.post("/api/v1/campaigns/" + id + "/preview", manager, null).body();
        assertThat(preview.get("inCooldown").asInt()).isEqualTo(1);
        assertThat(preview.get("eligible").asInt()).isZero();
        JsonNode second = run(id);
        assertThat(second.get("sent").asInt()).isZero();
        assertThat(second.get("skipped").asInt()).isEqualTo(1);

        // the member renews (no code) inside the attribution window -> conversion with revenue
        Api.Resp renewal = api.post("/api/v1/subscriptions/" + subId + "/renew", desk, Map.of());
        assertThat(renewal.status()).as(renewal.toString()).isEqualTo(201);
        pay(desk, renewal.body().get("invoiceId").asLong());
        JsonNode stats = api.get("/api/v1/campaigns/" + id, manager).body().get("stats");
        assertThat(stats.get("sent").asLong()).isEqualTo(1);
        assertThat(stats.get("converted").asLong()).isEqualTo(1);
        assertThat(stats.get("revenue").decimalValue()).isEqualByComparingTo("1180.00");
        assertThat(stats.get("runs").asLong()).isEqualTo(2);
        JsonNode recipients = api.get("/api/v1/campaigns/" + id + "/recipients?converted=true", manager).body();
        assertThat(recipients.get("totalElements").asLong()).isEqualTo(1);
        assertThat(recipients.at("/content/0/offerRedeemed").asBoolean()).isFalse();
        assertThat(api.get("/api/v1/campaigns/" + id + "/runs", manager).body().size()).isEqualTo(2);
    }

    @Test
    void lapsedWinBackWithPersonalSingleUseOfferCode() {
        long lapsed = deskMember("Rohit", unique("rohit") + "@example.com", "+91 97000 00001");
        subscribeAndPay(desk, lapsed, planId);
        long other = deskMember("Other", unique("oth") + "@example.com", "+91 97000 00002");
        advance(Duration.ofDays(42));
        runLifecycle();
        assertThat(jdbc.queryForObject("select status from subscriptions where member_id = ?", String.class, lapsed))
                .isEqualTo("EXPIRED");

        long id = create(campaign("LAPSED", Map.of("segmentDays", 7, "segmentDaysMax", 90, "offerEnabled", true,
                "offerDiscountType", "PERCENTAGE", "offerValue", 20, "offerValidDays", 14,
                "messageTemplate", "We miss you {firstName}! Use {offerCode} for 20% off.")));
        JsonNode result = run(id);
        assertThat(result.get("sent").asInt()).isEqualTo(1);
        String code = jdbc.queryForObject("select offer_code from campaign_recipients where campaign_id = ?", String.class, id);
        assertThat(code).startsWith("WB");
        assertThat(count("select count(*) from personal_discount_codes p join discounts d on d.id = p.discount_id "
                + "where d.code = ? and p.member_id = ? and d.max_redemptions = 1", code, lapsed)).isEqualTo(1);

        // someone else cannot use it
        Api.Resp stolen = subscribeWithCode(desk, other, code);
        assertThat(stolen.status()).as(stolen.toString()).isEqualTo(422);

        // the member comes back with it -> converted, offer redeemed
        Api.Resp back = subscribeWithCode(desk, lapsed, code);
        assertThat(back.status()).as(back.toString()).isEqualTo(201);
        pay(desk, back.body().get("invoiceId").asLong());
        JsonNode recipient = api.get("/api/v1/campaigns/" + id + "/recipients", manager).body().at("/content/0");
        assertThat(recipient.get("convertedAt").isNull()).isFalse();
        assertThat(recipient.get("offerRedeemed").asBoolean()).isTrue();
        JsonNode stats = api.get("/api/v1/campaigns/" + id, manager).body().get("stats");
        assertThat(stats.get("offersRedeemed").asLong()).isEqualTo(1);

        // single use: a second purchase with the same code is refused
        long subId = back.id();
        Api.Resp again = api.post("/api/v1/subscriptions/" + subId + "/renew", desk, Map.of("discountCode", code));
        assertThat(again.status()).as(again.toString()).isEqualTo(422);
    }

    @Test
    void churnRiskSegmentUsesAnalyticsAndDegradesClearlyWhenItIsDown() {
        long risky = deskMember("Anjali", unique("anj") + "@example.com", "+91 96000 00001");
        subscribeAndPay(desk, risky, planId);
        long elsewhere = deskMember("Aman", unique("aman") + "@example.com", "+91 96000 00002");
        when(churnRisk.membersAtRisk(eq(ChurnBand.HIGH), eq(branchId), anyInt())).thenReturn(List.of(risky, 999_999L));

        Map<String, Object> body = campaign("CHURN_RISK", Map.of("churnBand", "HIGH"));
        Api.Resp preview = api.post("/api/v1/campaigns/preview", manager, body);
        assertThat(preview.status()).as(preview.toString()).isEqualTo(200);
        assertThat(preview.body().get("audienceSize").asInt()).isEqualTo(1);
        long id = create(body);
        assertThat(run(id).get("sent").asInt()).isEqualTo(1);
        assertThat(count("select count(*) from campaign_recipients where member_id = ?", elsewhere)).isZero();

        when(churnRisk.membersAtRisk(any(), any(), anyInt()))
                .thenThrow(new ChurnRiskUnavailableException("connection refused", null));
        Api.Resp down = api.post("/api/v1/campaigns/" + id + "/preview", manager, null);
        assertThat(down.status()).isEqualTo(503);
        assertThat(down.body().get("code").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(down.body().get("message").asText()).contains("Other segments still work");
        Api.Resp failedRun = api.post("/api/v1/campaigns/" + id + "/run", manager, null);
        assertThat(failedRun.status()).isEqualTo(503);
        assertThat(jdbc.queryForObject("select status from campaign_runs where campaign_id = ? order by id desc limit 1",
                String.class, id)).isEqualTo("FAILED");
        // the run lock was released, and other segments are unaffected
        assertThat(api.post("/api/v1/campaigns/preview", manager, campaign("EXPIRING_NO_AUTOPAY",
                Map.of("segmentDays", 40))).status()).isEqualTo(200);
    }

    @Test
    void weeklyCampaignRunsFromTheScheduledJob() {
        long member = deskMember("Harsh", unique("harsh") + "@example.com", "+91 95000 00001");
        subscribeAndPay(desk, member, planId);
        long id = create(campaign("NO_RECENT_VISIT", Map.of("segmentDays", 1, "schedule", "WEEKLY",
                "scheduleDayOfWeek", 1)));
        assertThat(api.post("/api/v1/campaigns/" + id + "/pause", manager, null).status()).as("not active yet").isEqualTo(409);
        JsonNode active = api.post("/api/v1/campaigns/" + id + "/activate", manager, null).body();
        assertThat(active.get("status").asText()).isEqualTo("ACTIVE");
        Instant next = Instant.parse(active.get("nextRunAt").asText());
        assertThat(next).isAfter(Instant.now(clock));

        // nothing due yet
        runGrowthJobs();
        assertThat(count("select count(*) from campaign_runs where campaign_id = ?", id)).isZero();

        advance(Duration.ofDays(3));   // the member registered "3 days ago" and has not visited since
        jdbc.update("update campaigns set next_run_at = ? where id = ?", Timestamp.from(Instant.now(clock).minusSeconds(60)), id);
        runGrowthJobs();
        assertThat(jdbc.queryForObject("select trigger_type from campaign_runs where campaign_id = ?", String.class, id))
                .isEqualTo("SCHEDULED");
        assertThat(count("select count(*) from campaign_recipients where campaign_id = ? and member_id = ?", id, member))
                .isEqualTo(1);
        JsonNode after = api.get("/api/v1/campaigns/" + id, manager).body();
        assertThat(Instant.parse(after.get("nextRunAt").asText())).isAfter(Instant.now(clock));

        JsonNode paused = api.post("/api/v1/campaigns/" + id + "/pause", manager, null).body();
        assertThat(paused.get("status").asText()).isEqualTo("PAUSED");
        assertThat(paused.get("nextRunAt").isNull()).isTrue();
        assertThat(api.post("/api/v1/campaigns/" + id + "/archive", manager, null).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/campaigns/" + id + "/run", manager, null).status()).isEqualTo(422);
    }

    @Test
    void validationAndBranchScoping() {
        Api.Resp unknown = api.post("/api/v1/campaigns", manager, campaign("LAPSED", Map.of("segmentDays", 7,
                "segmentDaysMax", 90, "messageTemplate", "Hi {name}")));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.body().get("message").asText()).contains("Unknown placeholder {name}");
        Api.Resp offerless = api.post("/api/v1/campaigns", manager, campaign("LAPSED", Map.of("segmentDays", 7,
                "segmentDaysMax", 90, "messageTemplate", "Code {offerCode}")));
        assertThat(offerless.status()).isEqualTo(400);
        assertThat(api.post("/api/v1/campaigns", manager, campaign("CHURN_RISK", Map.of())).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/campaigns", manager, campaign("LAPSED", Map.of("segmentDays", 30,
                "segmentDaysMax", 7))).status()).isEqualTo(400);

        Map<String, Object> orgWide = campaign("LAPSED", Map.of("segmentDays", 7, "segmentDaysMax", 90));
        orgWide.remove("branchId");
        assertThat(api.post("/api/v1/campaigns", manager, orgWide).status()).as("managers pick a branch").isEqualTo(403);
        assertThat(api.post("/api/v1/campaigns", admin(), orgWide).status()).isEqualTo(201);

        long id = create(campaign("LAPSED", Map.of("segmentDays", 7, "segmentDaysMax", 90)));
        String admin = admin();
        long otherBranch = createBranch(admin);
        String otherManager = login(createStaff(admin, "MANAGER", otherBranch), STAFF_PASSWORD);
        assertThat(api.get("/api/v1/campaigns/" + id, otherManager).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/campaigns/" + id + "/run", otherManager, null).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/campaigns", desk).status()).as("receptionists").isEqualTo(403);
        JsonNode mine = api.get("/api/v1/campaigns?size=100", otherManager).body();
        assertThat(mine.get("totalElements").asLong()).isZero();
    }
}
