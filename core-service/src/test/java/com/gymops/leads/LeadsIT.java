package com.gymops.leads;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.gymops.support.Api;
import com.gymops.support.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Leads & trials end to end: public enquiry, scoping, trials, auto-conversion, follow-ups and jobs. */
class LeadsIT extends IntegrationTestBase {

    @Autowired LeadJobs jobs;

    private static String phone() {
        return "+91 9" + (100_000_000 + ThreadLocalRandom.current().nextInt(899_999_999));
    }

    private static Map<String, Object> enquiry(long branchId, String phone) {
        Map<String, Object> body = new HashMap<>(Map.of("branchId", branchId, "firstName", "Asha", "lastName", "Rao",
                "email", unique("asha") + "@example.com", "phone", phone, "interest", "MEMBERSHIP",
                "message", "Evening batches?", "consent", true));
        body.put("website", "");
        return body;
    }

    private long createLead(String token, long branchId, String phone) {
        Api.Resp r = api.post("/api/v1/leads", token, Map.of("branchId", branchId, "firstName", "Vikram",
                "lastName", "Shah", "email", unique("vikram") + "@example.com", "phone", phone, "source", "WALK_IN"));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.id();
    }

    private long userId(String email) {
        Long id = jdbc.queryForObject("select id from users where email = ?", Long.class, email);
        assertThat(id).isNotNull();
        return id;
    }

    private long freeTrialPlan(String admin) {
        return createPlan(admin, "CUSTOM", "0.00", "NON_REFUNDABLE", 0, Map.of("customDurationDays", 3,
                "freezeAllowed", false, "minFreezeDays", 0, "maxFreezeDays", 0, "maxFreezeCount", 0));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ public enquiry

    @Test
    void publicEnquiryNeedsNoLoginAndMergesRepeatEnquiries() {
        long branchId = createBranch(admin());
        String phone = phone();
        String normalized = Lead.normalizePhone(phone);

        Api.Resp first = api.post("/api/v1/leads/enquiry", null, enquiry(branchId, phone));
        assertThat(first.status()).as(first.toString()).isEqualTo(202);
        Map<String, Object> repeat = enquiry(branchId, phone.replace(" ", ""));
        repeat.put("message", "Also interested in PT");
        Api.Resp second = api.post("/api/v1/leads/enquiry", null, repeat);
        assertThat(second.status()).isEqualTo(202);
        assertThat(second.body()).as("same answer whether or not the lead existed").isEqualTo(first.body());

        assertThat(count("select count(*) from leads where branch_id = ? and phone = ?", branchId, normalized)).isEqualTo(1);
        Map<String, Object> lead = jdbc.queryForMap("select id, source, status from leads where branch_id = ? and phone = ?",
                branchId, normalized);
        assertThat(lead.get("source")).isEqualTo("WEBSITE");
        assertThat(lead.get("status")).isEqualTo("NEW");
        assertThat(count("select count(*) from lead_activities where lead_id = ? and type = 'NOTE'", lead.get("id")))
                .isEqualTo(2);
        assertThat(count("select count(*) from audit_logs where entity_type = 'LEAD' and entity_id = ?", lead.get("id")))
                .isEqualTo(2);
    }

    @Test
    void enquiryHoneypotIsDroppedAndConsentIsRequired() {
        long branchId = createBranch(admin());
        String phone = phone();
        Map<String, Object> bot = enquiry(branchId, phone);
        bot.put("website", "http://spam.example");
        Api.Resp r = api.post("/api/v1/leads/enquiry", null, bot);
        assertThat(r.status()).as("bots get the normal answer").isEqualTo(202);
        assertThat(count("select count(*) from leads where branch_id = ?", branchId)).isZero();

        Map<String, Object> noConsent = enquiry(branchId, phone);
        noConsent.put("consent", false);
        assertThat(api.post("/api/v1/leads/enquiry", null, noConsent).status()).isEqualTo(400);
        Map<String, Object> link = enquiry(branchId, phone);
        link.put("firstName", "<a href=x>win</a>");
        assertThat(api.post("/api/v1/leads/enquiry", null, link).status()).isEqualTo(400);
        assertThat(count("select count(*) from leads where branch_id = ?", branchId)).isZero();

        assertThat(api.get("/api/v1/leads", null).status()).as("staff endpoints stay private").isEqualTo(401);
    }

    // ------------------------------------------------------------------ scoping & workflow

    @Test
    void staffOnlySeeLeadsOfTheirBranches() {
        String admin = admin();
        long branchA = createBranch(admin);
        long branchB = createBranch(admin);
        String deskA = login(createStaff(admin, "RECEPTIONIST", branchA), STAFF_PASSWORD);
        String deskB = login(createStaff(admin, "RECEPTIONIST", branchB), STAFF_PASSWORD);
        long leadA = createLead(deskA, branchA, phone());
        long leadB = createLead(deskB, branchB, phone());

        assertThat(api.get("/api/v1/leads/" + leadB, deskA).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/leads?branchId=" + branchB, deskA).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/leads", deskA, Map.of("branchId", branchB, "firstName", "X", "phone", phone(),
                "source", "PHONE")).status()).isEqualTo(403);
        JsonNode mine = api.get("/api/v1/leads?size=100", deskA).body().get("content");
        assertThat(mine.findValues("branchId")).allMatch(n -> n.asLong() == branchA);
        assertThat(mine.findValues("id")).anyMatch(n -> n.asLong() == leadA);
        assertThat(api.get("/api/v1/leads/" + leadA, deskA).body().get("lead").get("ownerName").asText())
                .as("front desk creating a walk-in owns it").isEqualTo("Staff RECEPTIONIST");

        String member = login(createStaff(admin, "TRAINER", branchA), STAFF_PASSWORD);
        assertThat(api.get("/api/v1/leads", member).status()).as("trainers are not front desk").isEqualTo(403);
        assertThat(api.get("/api/v1/leads/summary", admin).status()).isEqualTo(200);
    }

    @Test
    void duplicateOpenPhoneIsRejectedForStaffAndOwnerMustBeBranchStaff() {
        String admin = admin();
        long branchId = createBranch(admin);
        long otherBranch = createBranch(admin);
        String desk = login(createStaff(admin, "RECEPTIONIST", branchId), STAFF_PASSWORD);
        String phone = phone();
        long lead = createLead(desk, branchId, phone);
        Api.Resp dup = api.post("/api/v1/leads", desk, Map.of("branchId", branchId, "firstName", "Dup", "phone", phone,
                "source", "PHONE"));
        assertThat(dup.status()).as(dup.toString()).isEqualTo(409);

        long outsider = userId(createStaff(admin, "RECEPTIONIST", otherBranch));
        assertThat(api.patch("/api/v1/leads/" + lead + "/owner", desk, Map.of("ownerUserId", outsider)).status())
                .isEqualTo(422);
        String managerEmail = createStaff(admin, "MANAGER", branchId);
        long manager = userId(managerEmail);
        Api.Resp assigned = api.patch("/api/v1/leads/" + lead + "/owner", desk, Map.of("ownerUserId", manager));
        assertThat(assigned.status()).as(assigned.toString()).isEqualTo(200);
        assertThat(count("select count(*) from notifications where type = 'LEAD_ASSIGNED' and recipient_user_id = ?",
                manager)).isEqualTo(1);
        assertThat(api.get("/api/v1/leads/owners?branchId=" + branchId, desk).body().findValues("userId"))
                .anyMatch(n -> n.asLong() == manager);
    }

    @Test
    void lostNeedsReasonAndCanBeReopened() {
        String admin = admin();
        long branchId = createBranch(admin);
        String desk = login(createStaff(admin, "RECEPTIONIST", branchId), STAFF_PASSWORD);
        long lead = createLead(desk, branchId, phone());

        Api.Resp call = api.post("/api/v1/leads/" + lead + "/activities", desk, Map.of("type", "CALL", "body", "Called"));
        assertThat(call.status()).as(call.toString()).isEqualTo(201);
        assertThat(api.get("/api/v1/leads/" + lead, desk).body().get("lead").get("status").asText())
                .as("a logged call moves a NEW lead to CONTACTED").isEqualTo("CONTACTED");
        assertThat(api.post("/api/v1/leads/" + lead + "/activities", desk, Map.of("type", "STATUS_CHANGE", "body", "x"))
                .status()).isEqualTo(400);

        assertThat(api.patch("/api/v1/leads/" + lead + "/status", desk, Map.of("status", "LOST")).status()).isEqualTo(422);
        assertThat(api.patch("/api/v1/leads/" + lead + "/status", desk, Map.of("status", "TRIAL_ACTIVE")).status())
                .isEqualTo(422);
        Api.Resp lost = api.patch("/api/v1/leads/" + lead + "/status", desk,
                Map.of("status", "LOST", "lostReason", "PRICE", "note", "Too expensive"));
        assertThat(lost.status()).as(lost.toString()).isEqualTo(200);
        assertThat(lost.body().get("lostReason").asText()).isEqualTo("PRICE");
        assertThat(api.patch("/api/v1/leads/" + lead + "/status", desk, Map.of("status", "CONTACTED")).status())
                .isEqualTo(409);

        JsonNode detail = api.get("/api/v1/leads/" + lead, desk).body();
        assertThat(detail.get("allowedStatusChanges").toString()).isEqualTo("[\"NEW\"]");
        assertThat(detail.get("activities").findValues("body")).anyMatch(b -> b.asText().contains("LOST (PRICE)"));

        Api.Resp reopened = api.patch("/api/v1/leads/" + lead + "/status", desk, Map.of("status", "NEW"));
        assertThat(reopened.status()).isEqualTo(200);
        assertThat(reopened.body().get("lostReason").isNull()).isTrue();
    }

    @Test
    void followUpsAppearInDueListUntilCompletedAndDigestIsSentOncePerDay() {
        String admin = admin();
        long branchId = createBranch(admin);
        String deskEmail = createStaff(admin, "RECEPTIONIST", branchId);
        long deskId = userId(deskEmail);
        String desk = login(deskEmail, STAFF_PASSWORD);
        long lead = createLead(desk, branchId, phone());

        Instant due = Instant.now(clock).plus(Duration.ofHours(1));
        Api.Resp f = api.post("/api/v1/leads/" + lead + "/follow-ups", desk, Map.of("dueAt", due.toString(),
                "body", "Call back about evening batch"));
        assertThat(f.status()).as(f.toString()).isEqualTo(201);
        long followUpId = f.id();
        assertThat(api.post("/api/v1/leads/" + lead + "/follow-ups", desk, Map.of("dueAt",
                Instant.now(clock).minus(Duration.ofDays(1)).toString(), "body", "past")).status()).isEqualTo(400);
        assertThat(api.get("/api/v1/leads/" + lead, desk).body().get("lead").get("nextFollowUpAt").isNull()).isFalse();

        clock.advance(Duration.ofHours(2));
        desk = login(deskEmail, STAFF_PASSWORD);
        JsonNode mine = api.get("/api/v1/leads/follow-ups", desk).body();
        assertThat(mine.findValues("activityId")).anyMatch(n -> n.asLong() == followUpId);
        assertThat(mine.findValues("overdue")).allMatch(JsonNode::asBoolean);
        assertThat(api.get("/api/v1/leads?followUpDue=true&branchId=" + branchId, desk).body().get("totalElements")
                .asLong()).isEqualTo(1);
        JsonNode summary = api.get("/api/v1/leads/summary?branchId=" + branchId, desk).body();
        assertThat(summary.get("myFollowUpsDue").asLong()).isEqualTo(1);
        assertThat(summary.get("byStatus").get("NEW").asLong()).isEqualTo(1);

        jobs.runFollowUpDigest();
        jobs.runFollowUpDigest();
        assertThat(count("select count(*) from notifications where type = 'LEAD_FOLLOW_UP_DUE' and recipient_user_id = ?",
                deskId)).as("one digest per owner per day").isEqualTo(1);

        Api.Resp done = api.post("/api/v1/leads/" + lead + "/follow-ups/" + followUpId + "/complete", desk,
                Map.of("outcome", "Booked a tour"));
        assertThat(done.status()).as(done.toString()).isEqualTo(200);
        assertThat(api.post("/api/v1/leads/" + lead + "/follow-ups/" + followUpId + "/complete", desk, Map.of())
                .status()).isEqualTo(422);
        assertThat(api.get("/api/v1/leads/follow-ups", desk).body().findValues("activityId"))
                .noneMatch(n -> n.asLong() == followUpId);
        assertThat(api.get("/api/v1/leads/" + lead, desk).body().get("lead").get("nextFollowUpAt").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ trials & conversion

    @Test
    void freeTrialRegistersMemberActivatesSubscriptionAndPaidPlanConvertsTheLead() {
        String admin = admin();
        long branchId = createBranch(admin);
        String desk = login(createStaff(admin, "RECEPTIONIST", branchId), STAFF_PASSWORD);
        long lead = createLead(desk, branchId, phone());
        long trialPlan = freeTrialPlan(admin);

        Api.Resp trial = api.post("/api/v1/leads/" + lead + "/trial", desk, Map.of("planId", trialPlan));
        assertThat(trial.status()).as(trial.toString()).isEqualTo(201);
        JsonNode t = trial.body();
        assertThat(t.get("subscriptionStatus").asText()).isEqualTo("ACTIVE");
        assertThat(t.get("paymentDue").asBoolean()).isFalse();
        assertThat(t.get("lead").get("status").asText()).as("the trial's own activation is not a conversion")
                .isEqualTo("TRIAL_ACTIVE");
        long memberId = t.get("memberId").asLong();
        long trialSubId = t.get("subscriptionId").asLong();
        LocalDate trialEnd = LocalDate.parse(t.get("endDate").asText());
        assertThat(trialEnd).isEqualTo(LocalDate.now(clock).plusDays(2));
        assertThat(t.get("lead").get("trialEndsOn").asText()).isEqualTo(trialEnd.toString());
        assertThat(jdbc.queryForObject("select status from subscriptions where id = ?", String.class, trialSubId))
                .isEqualTo("ACTIVE");
        assertThat(count("select count(*) from notifications where member_id = ? and type = 'TRIAL_STARTED'", memberId))
                .isPositive();
        assertThat(api.get("/api/v1/leads/" + lead, desk).body().get("member").get("id").asLong()).isEqualTo(memberId);
        assertThat(api.post("/api/v1/leads/" + lead + "/trial", desk, Map.of("planId", trialPlan)).status())
                .as("only one trial per pipeline pass").isEqualTo(409);

        long paidPlan = createPlan(admin, "MONTHLY", "2500.00", "PRORATED", 3, Map.of());
        Api.Resp sub = api.post("/api/v1/subscriptions", desk, Map.of("memberId", memberId, "planId", paidPlan,
                "startDate", trialEnd.plusDays(1).toString()));
        assertThat(sub.status()).as(sub.toString()).isEqualTo(201);
        assertThat(api.get("/api/v1/leads/" + lead, desk).body().get("lead").get("status").asText())
                .as("unpaid purchase does not convert").isEqualTo("TRIAL_ACTIVE");
        Api.Resp pay = payInvoice(desk, sub.body().get("invoiceId").asLong(), unique("lead-pay-"));
        assertThat(pay.status()).as(pay.toString()).isEqualTo(201);

        JsonNode converted = api.get("/api/v1/leads/" + lead, desk).body();
        assertThat(converted.get("lead").get("status").asText()).isEqualTo("CONVERTED");
        assertThat(converted.get("lead").get("convertedAt").isNull()).isFalse();
        assertThat(converted.get("canStartTrial").asBoolean()).isFalse();
        assertThat(converted.get("activities").findValues("body")).anyMatch(b -> b.asText().contains("purchased"));
    }

    @Test
    void paidTrialAwaitsPaymentAndPayingItDoesNotConvert() {
        String admin = admin();
        long branchId = createBranch(admin);
        String desk = login(createStaff(admin, "RECEPTIONIST", branchId), STAFF_PASSWORD);
        long lead = createLead(desk, branchId, phone());
        long paidTrial = createPlan(admin, "CUSTOM", "499.00", "NON_REFUNDABLE", 0, Map.of("customDurationDays", 7));

        Api.Resp trial = api.post("/api/v1/leads/" + lead + "/trial", desk, Map.of("planId", paidTrial));
        assertThat(trial.status()).as(trial.toString()).isEqualTo(201);
        assertThat(trial.body().get("paymentDue").asBoolean()).isTrue();
        assertThat(trial.body().get("subscriptionStatus").asText()).isEqualTo("PENDING_PAYMENT");
        Api.Resp pay = payInvoice(desk, trial.body().get("invoiceId").asLong(), unique("trial-pay-"));
        assertThat(pay.status()).as(pay.toString()).isEqualTo(201);
        assertThat(api.get("/api/v1/leads/" + lead, desk).body().get("lead").get("status").asText())
                .isEqualTo("TRIAL_ACTIVE");

        long archived = createPlan(admin, "CUSTOM", "0.00", "NON_REFUNDABLE", 0, Map.of("customDurationDays", 3));
        api.patch("/api/v1/plans/" + archived + "/status", admin, Map.of("status", "ARCHIVED"));
        long other = createLead(desk, branchId, phone());
        assertThat(api.post("/api/v1/leads/" + other + "/trial", desk, Map.of("planId", archived)).status())
                .as("plan must be ACTIVE").isEqualTo(422);
        assertThat(count("select count(*) from leads where id = ? and member_id is null", other)).isEqualTo(1);
    }

    @Test
    void existingMemberIsLinkedAndManualConvertWorks() {
        String admin = admin();
        long branchId = createBranch(admin);
        String desk = login(createStaff(admin, "RECEPTIONIST", branchId), STAFF_PASSWORD);
        long memberId = createMember(desk, branchId);
        String email = jdbc.queryForObject("select email from members where id = ?", String.class, memberId);
        Api.Resp created = api.post("/api/v1/leads", desk, Map.of("branchId", branchId, "firstName", "Test",
                "lastName", "Member", "email", email, "phone", phone(), "source", "REFERRAL"));
        long lead = created.id();
        Api.Resp trial = api.post("/api/v1/leads/" + lead + "/trial", desk, Map.of("planId", freeTrialPlan(admin)));
        assertThat(trial.status()).as(trial.toString()).isEqualTo(201);
        assertThat(trial.body().get("existingMemberLinked").asBoolean()).isTrue();
        assertThat(trial.body().get("memberId").asLong()).isEqualTo(memberId);

        long other = createLead(desk, branchId, phone());
        Api.Resp converted = api.post("/api/v1/leads/" + other + "/convert", desk, Map.of("memberId", memberId));
        assertThat(converted.status()).as(converted.toString()).isEqualTo(200);
        assertThat(converted.body().get("lead").get("status").asText()).isEqualTo("CONVERTED");
        assertThat(converted.body().get("member").get("memberCode").asText()).startsWith("M");
    }

    @Test
    void trialEndingJobRemindsMemberAndRaisesFollowUpsOnce() {
        String admin = admin();
        long branchId = createBranch(admin);
        String deskEmail = createStaff(admin, "RECEPTIONIST", branchId);
        String desk = login(deskEmail, STAFF_PASSWORD);
        long lead = createLead(desk, branchId, phone());
        Api.Resp trial = api.post("/api/v1/leads/" + lead + "/trial", desk, Map.of("planId", freeTrialPlan(admin)));
        assertThat(trial.status()).as(trial.toString()).isEqualTo(201);
        long memberId = trial.body().get("memberId").asLong();

        int[] failures = {0};
        jobs.runTrialEndingReminders(failures);   // ends in 2 days: too early
        assertThat(count("select count(*) from notifications where member_id = ? and type = 'TRIAL_ENDING'", memberId))
                .isZero();

        clock.advance(Duration.ofDays(1));        // trial now ends tomorrow
        jobs.runTrialEndingReminders(failures);
        jobs.runTrialEndingReminders(failures);
        assertThat(count("select count(*) from notifications where member_id = ? and type = 'TRIAL_ENDING' and channel = 'EMAIL'",
                memberId)).as("email channel, de-duplicated").isEqualTo(1);
        assertThat(count("select count(*) from lead_activities where lead_id = ? and type = 'FOLLOW_UP' "
                + "and body like 'Trial ends on%'", lead)).isEqualTo(1);
        LocalDate trialEnd = LocalDate.now(clock).plusDays(1);
        Instant expectedDue = trialEnd.atTime(10, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        assertThat(jdbc.queryForObject("select next_follow_up_at from leads where id = ?", java.sql.Timestamp.class, lead)
                .toInstant()).isEqualTo(expectedDue);

        clock.advance(Duration.ofDays(8));        // trial ended 7+ days ago without converting
        jobs.runStaleTrialChecks(failures);
        jobs.runStaleTrialChecks(failures);
        assertThat(count("select count(*) from lead_activities where lead_id = ? and type = 'FOLLOW_UP' and body = ?",
                lead, LeadJobService.STALE_TRIAL_TASK)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from leads where id = ?", String.class, lead))
                .isEqualTo("TRIAL_ACTIVE");
        assertThat(failures[0]).isZero();
    }
}
