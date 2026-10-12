package com.gymops.growth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.gymops.support.Api;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Referral programme end to end: shared link -> enquiry -> first paid membership -> held rewards ->
 * release after the hold; refund inside the hold reverses; fraud guards (self-referral, not new,
 * monthly cap); public lookup; personal welcome code is single-use and owner-only.
 */
class ReferralIT extends GrowthITBase {

    @BeforeEach
    void setUp() {
        setUpBranch();
    }

    private MemberLogin payingReferrer(String first) {
        MemberLogin referrer = registerMember(first);
        subscribeAndPay(desk, referrer.id(), planId);
        return referrer;
    }

    private String codeOf(MemberLogin m) {
        Api.Resp me = api.get("/api/v1/referrals/me", memberToken(m));
        assertThat(me.status()).as(me.toString()).isEqualTo(200);
        return me.body().get("code").asText();
    }

    @Test
    void sharedLinkEnquiryConvertsOnFirstPaidMembershipAndRewardsReleaseAfterTheHold() {
        MemberLogin referrer = payingReferrer("Asha");
        String code = codeOf(referrer);
        assertThat(code).matches("^ASHA[A-Z0-9]{3,}$");
        assertThat(codeOf(referrer)).as("stable").isEqualTo(code);

        // public lookup: first name + offer, nothing else
        Api.Resp lookup = api.get("/api/v1/public/referrals/" + code.toLowerCase(), null);
        assertThat(lookup.status()).as(lookup.toString()).isEqualTo(200);
        assertThat(lookup.body().get("referrerFirstName").asText()).isEqualTo("Asha");
        assertThat(lookup.body().has("email")).isFalse();
        assertThat(lookup.body().toString()).doesNotContain("Tester").doesNotContain(referrer.email());
        assertThat(api.get("/api/v1/public/referrals/NOPE99X", null).status()).isEqualTo(404);

        // friend enquires through the link
        String email = unique("rahul") + "@example.com";
        Api.Resp enquiry = api.post("/api/v1/leads/enquiry", null, Map.of("branchId", branchId, "firstName", "Rahul",
                "lastName", "Sharma", "email", email, "phone", "+91 98111 22334", "consent", true, "referralCode", code));
        assertThat(enquiry.status()).as(enquiry.toString()).isEqualTo(202);
        long leadId = jdbc.queryForObject("select id from leads where lower(email) = ?", Long.class, email);
        assertThat(jdbc.queryForObject("select source from leads where id = ?", String.class, leadId)).isEqualTo("REFERRAL");
        assertThat(jdbc.queryForObject("select status from referrals where referee_lead_id = ?", String.class, leadId))
                .isEqualTo("PENDING");
        assertThat(count("select count(*) from notifications where member_id = ? and type = 'REFERRAL_SIGNED_UP'",
                referrer.id())).isPositive();
        // a second enquiry from the same person does not create a second referral
        api.post("/api/v1/leads/enquiry", null, Map.of("branchId", branchId, "firstName", "Rahul", "email", email,
                "phone", "+91 98111 22334", "consent", true, "referralCode", code));
        assertThat(count("select count(*) from referrals where referee_lead_id = ?", leadId)).isEqualTo(1);

        // the friend joins at the desk (lead not linked: matched by email) and pays
        long referee = deskMember("Rahul", email, "+91 98111 22334");
        assertThat(referralOf(referee)).as("not linked before buying").isNull();
        subscribeAndPay(desk, referee, planId);
        JsonNode referral = referralOf(referee);
        assertThat(referral.get("status").asText()).isEqualTo("CONVERTED");
        assertThat(referral.get("referrerRewardStatus").asText()).isEqualTo("HELD");
        assertThat(referral.get("refereeRewardStatus").asText()).isEqualTo("HELD");

        // inside the hold nothing is paid out
        runGrowthJobs();
        assertThat(credit(referrer.id())).isEqualByComparingTo("0");

        advance(Duration.ofDays(15));
        runGrowthJobs();
        assertThat(credit(referrer.id())).isEqualByComparingTo("500.00");
        JsonNode dashboard = api.get("/api/v1/referrals/me", memberToken(referrer)).body();
        assertThat(dashboard.get("joined").asLong()).isEqualTo(1);
        assertThat(dashboard.get("creditEarned").decimalValue()).isEqualByComparingTo("500");
        assertThat(dashboard.at("/referrals/0/refereeName").asText()).isEqualTo("Rahul S.");
        assertThat(dashboard.at("/referrals/0/reward/status").asText()).isEqualTo("AVAILABLE");
        assertThat(count("select count(*) from notifications where member_id = ? and type = 'REFERRAL_REWARD_EARNED'",
                referrer.id())).isPositive();
        // idempotent: a second run pays nothing more
        runGrowthJobs();
        assertThat(credit(referrer.id())).isEqualByComparingTo("500.00");

        // the referee's welcome code: personal and single use
        String welcome = jdbc.queryForObject("""
                select d.code from referral_rewards w join discounts d on d.id = w.discount_id
                where w.member_id = ? and w.beneficiary = 'REFEREE' and w.status = 'AVAILABLE'
                """, String.class, referee);
        long stranger = deskMember("Stranger", unique("s") + "@example.com", "+91 90000 11111");
        Api.Resp stolen = subscribeWithCode(desk, stranger, welcome);
        assertThat(stolen.status()).as(stolen.toString()).isEqualTo(422);
        assertThat(stolen.body().get("message").asText()).contains("belongs to another member");

        long currentSub = jdbc.queryForObject("select id from subscriptions where member_id = ? and status = 'ACTIVE'",
                Long.class, referee);
        Api.Resp renewal = api.post("/api/v1/subscriptions/" + currentSub + "/renew", desk, Map.of("discountCode", welcome));
        assertThat(renewal.status()).as(renewal.toString()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select discount_amount from invoices where id = ?", java.math.BigDecimal.class,
                renewal.body().get("invoiceId").asLong())).isEqualByComparingTo("100.00");
        long another = deskMember("Again", unique("a") + "@example.com", "+91 90000 22222");
        assertThat(subscribeWithCode(desk, another, welcome).status()).as("single use").isEqualTo(422);
    }

    @Test
    void deskReferralRefundedInsideTheHoldIsReversed() {
        MemberLogin referrer = payingReferrer("Kavya");
        String code = codeOf(referrer);
        long referee = deskMember("Varun", unique("varun") + "@example.com", "+91 97000 10001");
        Api.Resp attached = api.post("/api/v1/referrals", desk, Map.of("code", code, "memberId", referee));
        assertThat(attached.status()).as(attached.toString()).isEqualTo(201);
        assertThat(attached.body().get("status").asText()).isEqualTo("PENDING");
        assertThat(attached.body().get("channel").asText()).isEqualTo("DESK");
        assertThat(api.post("/api/v1/referrals", desk, Map.of("code", code, "memberId", referee)).status())
                .as("one referral per referee").isEqualTo(409);

        Api.Resp sub = subscribeWithCode(desk, referee, null);
        long paymentId = pay(desk, sub.body().get("invoiceId").asLong());
        assertThat(referralOf(referee).get("status").asText()).isEqualTo("CONVERTED");

        Api.Resp refund = api.post("/api/v1/payments/" + paymentId + "/refunds", manager,
                Map.of("amount", "100.00", "reason", "Changed mind"), Map.of("Idempotency-Key", unique("rf-")));
        assertThat(refund.status()).as(refund.toString()).isEqualTo(201);
        JsonNode reversed = referralOf(referee);
        assertThat(reversed.get("status").asText()).isEqualTo("REVERSED");
        assertThat(reversed.get("reversalReason").asText()).contains("refunded");
        assertThat(reversed.get("referrerRewardStatus").asText()).isEqualTo("REVERSED");

        advance(Duration.ofDays(15));
        runGrowthJobs();
        assertThat(credit(referrer.id())).isEqualByComparingTo("0");
    }

    @Test
    void cancellationInsideTheHoldReversesAndOneAfterTheHoldDoesNot() {
        MemberLogin referrer = payingReferrer("Ishita");
        String code = codeOf(referrer);
        long early = deskMember("Early", unique("e") + "@example.com", "+91 97000 20001");
        long late = deskMember("Late", unique("l") + "@example.com", "+91 97000 20002");
        assertThat(api.post("/api/v1/referrals", desk, Map.of("code", code, "memberId", early)).status()).isEqualTo(201);
        assertThat(api.post("/api/v1/referrals", desk, Map.of("code", code, "memberId", late)).status()).isEqualTo(201);
        long earlySub = subscribeAndPay(desk, early, planId);
        long lateSub = subscribeAndPay(desk, late, planId);

        Api.Resp cancel = api.post("/api/v1/subscriptions/" + earlySub + "/cancel", manager,
                Map.of("reason", "Moving cities", "refund", false));
        assertThat(cancel.status()).as(cancel.toString()).isEqualTo(200);
        assertThat(referralOf(early).get("status").asText()).isEqualTo("REVERSED");

        advance(Duration.ofDays(15));
        runGrowthJobs();
        Api.Resp lateCancel = api.post("/api/v1/subscriptions/" + lateSub + "/cancel", manager,
                Map.of("reason", "Injury", "refund", false));
        assertThat(lateCancel.status()).as(lateCancel.toString()).isEqualTo(200);
        assertThat(referralOf(late).get("status").asText()).as("after the hold the reward stands").isEqualTo("CONVERTED");
        assertThat(credit(referrer.id())).isEqualByComparingTo("500.00");
    }

    @Test
    void selfReferralIsBlockedEverywhere() {
        MemberLogin referrer = payingReferrer("Pooja");
        String code = codeOf(referrer);
        Api.Resp claim = api.post("/api/v1/referrals/me/claim", memberToken(referrer), Map.of("code", code));
        assertThat(claim.status()).as(claim.toString()).isEqualTo(422);
        assertThat(claim.body().get("message").asText()).contains("cannot refer themselves");

        Api.Resp desk1 = api.post("/api/v1/referrals", desk, Map.of("code", code, "memberId", referrer.id()));
        assertThat(desk1.status()).isEqualTo(422);

        // a second account with the referrer's phone number
        long twin = deskMember("Pooja", unique("twin") + "@example.com", referrer.phone());
        assertThat(api.post("/api/v1/referrals", desk, Map.of("code", code, "memberId", twin)).status()).isEqualTo(422);

        // public enquiry with the referrer's own email: silently ignored (202, no referral)
        Api.Resp enquiry = api.post("/api/v1/leads/enquiry", null, Map.of("branchId", branchId, "firstName", "Pooja",
                "email", referrer.email(), "phone", "+91 95555 12345", "consent", true, "referralCode", code));
        assertThat(enquiry.status()).isEqualTo(202);
        assertThat(count("select count(*) from referrals where referrer_member_id = ?", referrer.id())).isZero();
    }

    @Test
    void refereeMustBeNewButTheDeskHasAGraceWindow() {
        MemberLogin referrer = payingReferrer("Dev");
        String code = codeOf(referrer);

        long justPaid = deskMember("Just", unique("j") + "@example.com", "+91 96000 30001");
        subscribeAndPay(desk, justPaid, planId);
        Api.Resp attach = api.post("/api/v1/referrals", desk, Map.of("code", code, "memberId", justPaid));
        assertThat(attach.status()).as(attach.toString()).isEqualTo(201);
        assertThat(attach.body().get("status").asText()).as("converted straight away (inside grace)").isEqualTo("CONVERTED");

        long oldCustomer = deskMember("Old", unique("o") + "@example.com", "+91 96000 30002");
        subscribeAndPay(desk, oldCustomer, planId);
        advance(Duration.ofDays(8));
        Api.Resp late = api.post("/api/v1/referrals", desk, Map.of("code", code, "memberId", oldCustomer));
        assertThat(late.status()).as(late.toString()).isEqualTo(422);
        assertThat(late.body().get("message").asText()).contains("new members only");

        // a self-registered member claims a code before buying
        MemberLogin fresh = registerMember("Tanvi");
        Api.Resp claim = api.post("/api/v1/referrals/me/claim", fresh.token(), Map.of("code", code));
        assertThat(claim.status()).as(claim.toString()).isEqualTo(200);
        assertThat(claim.body().get("referredBy").asText()).isEqualTo("Dev");
        subscribeAndPay(desk, fresh.id(), planId);
        assertThat(referralOf(fresh.id()).get("channel").asText()).isEqualTo("SELF_SIGNUP");
        assertThat(referralOf(fresh.id()).get("status").asText()).isEqualTo("CONVERTED");
    }

    @Test
    void monthlyCapStopsReferrerRewardsButNotWelcomeRewards() {
        MemberLogin referrer = payingReferrer("Yash");
        String code = codeOf(referrer);
        long[] referees = new long[3];
        for (int i = 0; i < 3; i++) {
            referees[i] = deskMember("Friend" + (char) ('A' + i), unique("f") + "@example.com", "+91 93000 4000" + i);
            assertThat(api.post("/api/v1/referrals", desk, Map.of("code", code, "memberId", referees[i])).status())
                    .isEqualTo(201);
            subscribeAndPay(desk, referees[i], planId);
        }
        JsonNode third = referralOf(referees[2]);
        assertThat(third.get("status").asText()).isEqualTo("CONVERTED");
        assertThat(third.get("referrerRewardSkipped").asText()).isEqualTo("MONTHLY_CAP");
        assertThat(third.get("referrerRewardStatus").isNull()).isTrue();
        assertThat(third.get("refereeRewardStatus").asText()).isEqualTo("HELD");
        assertThat(count("select count(*) from referral_rewards w join referrals r on r.id = w.referral_id "
                + "where r.referrer_member_id = ? and w.beneficiary = 'REFERRER'", referrer.id())).isEqualTo(2);

        JsonNode stats = api.get("/api/v1/referrals/stats?branchId=" + branchId, manager).body();
        assertThat(stats.get("cappedReferrals").asLong()).isPositive();
        assertThat(stats.at("/byStatus/CONVERTED").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(stats.get("topReferrers").size()).isPositive();
    }

    @Test
    void staffViewsAreBranchScoped() {
        MemberLogin referrer = payingReferrer("Simran");
        String code = codeOf(referrer);
        long referee = deskMember("Kunal", unique("k") + "@example.com", "+91 92000 50001");
        long referralId = api.post("/api/v1/referrals", desk, Map.of("code", code, "memberId", referee)).id();

        String admin = admin();
        long otherBranch = createBranch(admin);
        String otherManager = login(createStaff(admin, "MANAGER", otherBranch), STAFF_PASSWORD);
        assertThat(api.get("/api/v1/referrals/" + referralId, otherManager).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/referrals/members/" + referee, otherManager).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/referrals?branchId=" + branchId, otherManager).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/referrals/stats", referrer.token()).status()).as("members").isEqualTo(403);

        JsonNode summary = api.get("/api/v1/referrals/members/" + referrer.id(), desk).body();
        assertThat(summary.get("code").asText()).isEqualTo(code);
        assertThat(summary.get("sharePath").asText()).isEqualTo("/enquire?ref=" + code);
        assertThat(summary.get("referrals").asLong()).isEqualTo(1);
    }

    private java.math.BigDecimal credit(long memberId) {
        return api.get("/api/v1/members/" + memberId + "/credit", manager).body().get("balance").decimalValue();
    }
}
