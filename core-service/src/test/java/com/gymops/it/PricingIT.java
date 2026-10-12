package com.gymops.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.gymops.payment.autopay.AutopayJob;
import com.gymops.support.Api;
import com.gymops.support.IntegrationTestBase;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pricing end to end: scheduled price changes and history, existing-member protection, the autopay
 * notice rule, negotiated prices at the point of sale, and GST invoice / credit-note PDFs.
 */
class PricingIT extends IntegrationTestBase {

    private static final String VALID_GSTIN = "27AAPFU0939F1ZV";

    @Autowired AutopayJob autopayJob;

    private record Ctx(String admin, String desk, String manager, long branchId, String deskEmail, String managerEmail) {
    }

    private Ctx setup() {
        String admin = admin();
        long branchId = createBranch(admin);
        String deskEmail = createStaff(admin, "RECEPTIONIST", branchId);
        String managerEmail = createStaff(admin, "MANAGER", branchId);
        return new Ctx(admin, login(deskEmail, STAFF_PASSWORD), login(managerEmail, STAFF_PASSWORD), branchId, deskEmail,
                managerEmail);
    }

    private Ctx relogin(Ctx c) {
        return new Ctx(admin(), login(c.deskEmail(), STAFF_PASSWORD), login(c.managerEmail(), STAFF_PASSWORD), c.branchId(),
                c.deskEmail(), c.managerEmail());
    }

    private Map<String, Object> planBody(String price) {
        return new HashMap<>(Map.of("code", unique("PR").replaceAll("[^A-Za-z0-9]", ""), "name", "Pricing plan",
                "billingPeriod", "MONTHLY", "price", price, "cancellationPolicy", "PRORATED", "gracePeriodDays", 3,
                "freezeAllowed", false, "minFreezeDays", 0, "maxFreezeDays", 0, "maxFreezeCount", 0));
    }

    private long plan(String admin, Map<String, Object> body) {
        Api.Resp r = api.post("/api/v1/plans", admin, body);
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        assertThat(api.patch("/api/v1/plans/" + r.id() + "/status", admin, Map.of("status", "ACTIVE")).status())
                .isEqualTo(200);
        return r.id();
    }

    private Api.Resp schedule(String token, long planId, Map<String, Object> body) {
        return api.post("/api/v1/pricing/plans/" + planId + "/changes", token, body);
    }

    private Map<String, Object> change(String newPrice, LocalDate effective, String policy) {
        return new HashMap<>(Map.of("newPrice", newPrice, "effectiveDate", effective.toString(),
                "existingMemberPolicy", policy, "reason", "Annual price revision"));
    }

    private JsonNode invoice(String token, long invoiceId) {
        return api.get("/api/v1/payments/invoices/" + invoiceId, token).body();
    }

    private void advanceTo(LocalDate date) {
        long days = ChronoUnit.DAYS.between(LocalDate.now(clock), date);
        clock.advance(Duration.ofDays(days).plusHours(1));
    }

    // ================================================================== scheduled changes & history

    @Test
    void scheduledChangeAppliesOnTheEffectiveDateAndIsKeptInHistory() {
        Ctx c = setup();
        long planId = plan(c.admin(), planBody("2000.00"));
        LocalDate effective = LocalDate.now(clock).plusDays(10);

        assertThat(schedule(c.desk(), planId, change("2500.00", effective, "NEW_PRICE_AT_RENEWAL")).status())
                .as("receptionists cannot change prices").isEqualTo(403);
        Map<String, Object> past = change("2500.00", LocalDate.now(clock).minusDays(1), "NEW_PRICE_AT_RENEWAL");
        assertThat(schedule(c.manager(), planId, past).status()).isEqualTo(400);

        Api.Resp preview = api.post("/api/v1/pricing/plans/" + planId + "/changes/preview", c.manager(),
                change("2500.00", effective, "NEW_PRICE_AT_RENEWAL"));
        assertThat(preview.status()).as(preview.toString()).isEqualTo(200);
        assertThat(preview.body().get("changePct").decimalValue()).isEqualByComparingTo("25.0");
        assertThat(preview.body().get("increase").asBoolean()).isTrue();

        Api.Resp scheduled = schedule(c.manager(), planId, change("2500.00", effective, "NEW_PRICE_AT_RENEWAL"));
        assertThat(scheduled.status()).as(scheduled.toString()).isEqualTo(201);
        assertThat(scheduled.body().get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(scheduled.body().get("createdByName").asText()).isEqualTo("Staff MANAGER");
        assertThat(schedule(c.manager(), planId, change("2600.00", effective, "NEW_PRICE_AT_RENEWAL")).status())
                .as("one pending change per plan").isEqualTo(409);

        Map<String, Object> edit = planBody("2100.00");
        assertThat(api.send("PUT", "/api/v1/plans/" + planId, c.admin(), edit, Map.of()).status())
                .as("direct price edit while a change is scheduled").isEqualTo(409);
        assertThat(api.get("/api/v1/plans/" + planId, null).body().get("price").decimalValue()).isEqualByComparingTo("2000.00");
        assertThat(api.get("/api/v1/pricing/changes/scheduled", c.desk()).body().findValuesAsText("planId"))
                .contains(String.valueOf(planId));

        // the day it takes effect, new sales use the new price even before the hourly job ran
        advanceTo(effective);
        c = relogin(c);
        long member = createMember(c.desk(), c.branchId());
        long invoiceId = subscribe(c.desk(), member, planId).body().get("invoiceId").asLong();
        assertThat(invoice(c.desk(), invoiceId).get("subtotal").decimalValue()).isEqualByComparingTo("2500.00");

        Api.Resp run = api.post("/api/v1/pricing/run", c.admin(), null);
        assertThat(run.status()).as(run.toString()).isEqualTo(200);
        assertThat(run.body().get("changesApplied").asInt()).isPositive();
        assertThat(api.get("/api/v1/plans/" + planId, null).body().get("price").decimalValue())
                .as("catalogue cache evicted").isEqualByComparingTo("2500.00");
        JsonNode history = api.get("/api/v1/pricing/plans/" + planId + "/history", c.manager()).body();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("status").asText()).isEqualTo("APPLIED");
        assertThat(history.get(0).get("oldPrice").decimalValue()).isEqualByComparingTo("2000.00");
        assertThat(history.get(0).get("newPrice").decimalValue()).isEqualByComparingTo("2500.00");
        assertThat(history.get(0).get("reason").asText()).isEqualTo("Annual price revision");
        assertThat(history.get(0).get("appliedAt").isNull()).isFalse();
    }

    @Test
    void scheduledChangeCanBeCancelledAndDirectEditsAreRecorded() {
        Ctx c = setup();
        Map<String, Object> body = planBody("2000.00");
        long planId = plan(c.admin(), body);
        Api.Resp scheduled = schedule(c.manager(), planId, change("2400.00", LocalDate.now(clock).plusDays(5),
                "NEW_PRICE_AT_RENEWAL"));
        Api.Resp cancelled = api.post("/api/v1/pricing/changes/" + scheduled.id() + "/cancel", c.manager(),
                Map.of("reason", "Board decided against it"));
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(api.post("/api/v1/pricing/changes/" + scheduled.id() + "/cancel", c.manager(),
                Map.of("reason", "again")).status()).isEqualTo(409);

        body.put("price", "1800.00");
        body.put("priceChangeReason", "Festive pricing");
        Api.Resp edited = api.send("PUT", "/api/v1/plans/" + planId, c.admin(), body, Map.of());
        assertThat(edited.status()).as(edited.toString()).isEqualTo(200);
        JsonNode history = api.get("/api/v1/pricing/plans/" + planId + "/history", c.admin()).body();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("source").asText()).isEqualTo("DIRECT_EDIT");
        assertThat(history.get(0).get("oldPrice").decimalValue()).isEqualByComparingTo("2000.00");
        assertThat(history.get(0).get("newPrice").decimalValue()).isEqualByComparingTo("1800.00");
        assertThat(history.get(0).get("reason").asText()).isEqualTo("Festive pricing");
        assertThat(history.get(1).get("status").asText()).isEqualTo("CANCELLED");
    }

    // ================================================================== existing members

    @Test
    void protectedExistingMembersRenewAtTheirPriceWhileNewSalesPayTheNewPrice() {
        Ctx c = setup();
        long planId = plan(c.admin(), planBody("2000.00"));
        long existing = createMember(c.desk(), c.branchId());
        long existingSub = subscribeAndPay(c.desk(), existing, planId);

        Map<String, Object> body = change("2500.00", LocalDate.now(clock), "PROTECT_EXISTING");
        body.put("protectMonths", 3);
        Api.Resp applied = schedule(c.manager(), planId, body);
        assertThat(applied.status()).as(applied.toString()).isEqualTo(201);
        assertThat(applied.body().get("status").asText()).as("effective today applies at once").isEqualTo("APPLIED");
        assertThat(applied.body().get("protectedCount").asInt()).isEqualTo(1);
        JsonNode locked = api.get("/api/v1/subscriptions/" + existingSub, c.desk()).body();
        assertThat(locked.get("lockedPrice").decimalValue()).isEqualByComparingTo("2000.00");

        long newcomer = createMember(c.desk(), c.branchId());
        long newInvoice = subscribe(c.desk(), newcomer, planId).body().get("invoiceId").asLong();
        assertThat(invoice(c.desk(), newInvoice).get("subtotal").decimalValue()).isEqualByComparingTo("2500.00");

        Api.Resp renewal = api.post("/api/v1/subscriptions/" + existingSub + "/renew", c.desk(), Map.of());
        assertThat(renewal.status()).as(renewal.toString()).isEqualTo(201);
        assertThat(renewal.body().get("priceBasis").asText()).isEqualTo("PRICE_PROTECTED");
        assertThat(renewal.body().get("price").decimalValue()).isEqualByComparingTo("2000.00");
        assertThat(renewal.body().get("listPrice").decimalValue()).isEqualByComparingTo("2500.00");
        JsonNode inv = invoice(c.desk(), renewal.body().get("invoiceId").asLong());
        assertThat(inv.get("lines")).hasSize(2);
        assertThat(inv.at("/lines/0/amount").decimalValue()).isEqualByComparingTo("2500.00");
        assertThat(inv.at("/lines/1/type").asText()).isEqualTo("PRICE_ADJUSTMENT");
        assertThat(inv.at("/lines/1/amount").decimalValue()).isEqualByComparingTo("-500.00");
        assertThat(inv.get("subtotal").decimalValue()).isEqualByComparingTo("2000.00");
        assertThat(inv.get("taxAmount").decimalValue()).isEqualByComparingTo("360.00");
    }

    @Test
    void withoutProtectionExistingMembersRenewAtTheNewPrice() {
        Ctx c = setup();
        long planId = plan(c.admin(), planBody("2000.00"));
        long member = createMember(c.desk(), c.branchId());
        long sub = subscribeAndPay(c.desk(), member, planId);
        assertThat(schedule(c.manager(), planId, change("2300.00", LocalDate.now(clock), "NEW_PRICE_AT_RENEWAL"))
                .status()).isEqualTo(201);
        Api.Resp renewal = api.post("/api/v1/subscriptions/" + sub + "/renew", c.desk(), Map.of());
        assertThat(renewal.status()).as(renewal.toString()).isEqualTo(201);
        assertThat(renewal.body().get("price").decimalValue()).isEqualByComparingTo("2300.00");
        assertThat(renewal.body().get("priceBasis").asText()).isEqualTo("LIST");
    }

    // ================================================================== autopay notice rule

    private Api.Resp checkout(String token, long invoiceId) {
        return api.post("/api/v1/payments/checkout", token, Map.of("invoiceId", invoiceId, "method", "UPI", "saveMethod", true,
                "setupAutopay", true), Map.of("Idempotency-Key", unique("chk-")));
    }

    /** Subscribes and pays by UPI with "renew automatically"; returns the subscription. */
    private JsonNode subscribeWithAutopay(Ctx c, long memberId, long planId) {
        Api.Resp sub = subscribe(c.desk(), memberId, planId);
        Api.Resp start = checkout(c.desk(), sub.body().get("invoiceId").asLong());
        assertThat(start.status()).as(start.toString()).isEqualTo(201);
        Api.Resp sim = api.post("/api/v1/payments/checkout/" + start.id("intentId") + "/simulate", c.desk(),
                Map.of("success", true, "vpa", "asha@okhdfc"));
        Api.Resp done = api.post("/api/v1/payments/checkout/" + start.id("intentId") + "/confirm", c.desk(), Map.of(
                "providerPaymentId", sim.body().get("providerPaymentId").asText(),
                "providerOrderId", sim.body().get("providerOrderId").asText(),
                "signature", sim.body().get("signature").asText()));
        assertThat(done.body().get("autopayEnabled").asBoolean()).as(done.toString()).isTrue();
        return api.get("/api/v1/subscriptions/" + sub.id(), c.desk()).body();
    }

    private JsonNode autopayRenewalInvoice(Ctx c, long memberId) {
        JsonNode attempts = api.get("/api/v1/autopay/attempts?memberId=" + memberId, c.desk()).body().get("content");
        assertThat(attempts).hasSize(1);
        return invoice(c.desk(), attempts.get(0).get("invoiceId").asLong());
    }

    @Test
    void autopayRenewalAfterAPriceRiseIsChargedTheNewPriceOnlyWithTimelyNotice() {
        Ctx c = setup();
        long planId = plan(c.admin(), planBody("2000.00"));
        long onTime = createMember(c.desk(), c.branchId());
        JsonNode sub = subscribeWithAutopay(c, onTime, planId);
        LocalDate end = LocalDate.parse(sub.get("endDate").asText());

        Map<String, Object> body = planBody("2600.00");
        assertThat(api.send("PUT", "/api/v1/plans/" + planId, c.admin(), body, Map.of()).status())
                .as("immediate increase blocked for autopay plans").isEqualTo(422);

        Api.Resp preview = api.post("/api/v1/pricing/plans/" + planId + "/changes/preview", c.manager(),
                change("2500.00", LocalDate.now(clock), "NEW_PRICE_AT_RENEWAL"));
        assertThat(preview.body().get("autopayMembersAffected").asInt()).isEqualTo(1);
        assertThat(preview.body().at("/autopayMembers/0/firstDebitAtNewPrice").asText())
                .isEqualTo(end.minusDays(2).toString());

        assertThat(schedule(c.manager(), planId, change("2500.00", LocalDate.now(clock), "NEW_PRICE_AT_RENEWAL")).status())
                .isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from notifications where member_id = ? and type = 'PLAN_PRICE_CHANGED'",
                Long.class, onTime)).isPositive();

        advanceTo(end.minusDays(3));
        c = relogin(c);
        assertThat(autopayJob.run().renewalsScheduled()).isPositive();
        JsonNode renewalInvoice = autopayRenewalInvoice(c, onTime);
        assertThat(renewalInvoice.get("subtotal").decimalValue()).as("notified a month ahead").isEqualByComparingTo("2500.00");

        // settle the debit so no scheduled attempt is left behind for other tests sharing the database
        clock.advance(Duration.ofHours(25));
        assertThat(autopayJob.run().succeeded()).isPositive();
    }

    @Test
    void autopayRenewalStaysAtTheOldPriceWhenTheNoticeCameTooLate() {
        Ctx c = setup();
        long planId = plan(c.admin(), planBody("2000.00"));
        long late = createMember(c.desk(), c.branchId());
        JsonNode sub = subscribeWithAutopay(c, late, planId);
        LocalDate end = LocalDate.parse(sub.get("endDate").asText());

        advanceTo(end.minusDays(6));
        c = relogin(c);
        assertThat(schedule(c.manager(), planId, change("2500.00", LocalDate.now(clock), "NEW_PRICE_AT_RENEWAL")).status())
                .isEqualTo(201);
        long notified = jdbc.queryForObject("select count(*) from price_change_notices where member_id = ? and plan_id = ?",
                Long.class, late, planId);
        assertThat(notified).isEqualTo(1);

        advanceTo(end.minusDays(3));
        c = relogin(c);
        autopayJob.run();
        JsonNode renewalInvoice = autopayRenewalInvoice(c, late);
        assertThat(renewalInvoice.get("subtotal").decimalValue()).as("only 4 days' notice").isEqualByComparingTo("2000.00");
        assertThat(renewalInvoice.at("/lines/1/type").asText()).isEqualTo("PRICE_ADJUSTMENT");
        long renewalId = renewalInvoice.get("subscriptionId").asLong();
        assertThat(api.get("/api/v1/subscriptions/" + renewalId, c.desk()).body().get("priceBasis").asText())
                .isEqualTo("NOTICE_HELD");

        // the debit at the held price goes through (and leaves nothing scheduled for other tests)
        clock.advance(Duration.ofHours(25));
        assertThat(autopayJob.run().succeeded()).isPositive();
    }

    // ================================================================== negotiated prices

    @Test
    void negotiatedPriceAtThePointOfSaleIsLimitedByRoleAndShownAsASeparateLine() {
        Ctx c = setup();
        long planId = plan(c.admin(), planBody("2000.00"));
        Map<String, Object> sale = new HashMap<>(Map.of("planId", planId, "customPrice", "1500.00",
                "customPriceReason", "Corporate tie-up with Acme", "keepCustomPriceOnRenewal", true));

        sale.put("memberId", createMember(c.desk(), c.branchId()));
        assertThat(api.post("/api/v1/subscriptions", c.desk(), sale).status()).as("receptionist").isEqualTo(403);

        Map<String, Object> noReason = new HashMap<>(sale);
        noReason.remove("customPriceReason");
        assertThat(api.post("/api/v1/subscriptions", c.manager(), noReason).status()).isEqualTo(400);

        Map<String, Object> tooDeep = new HashMap<>(sale);
        tooDeep.put("customPrice", "900.00");
        Api.Resp denied = api.post("/api/v1/subscriptions", c.manager(), tooDeep);
        assertThat(denied.status()).as("55% exceeds the manager limit").isEqualTo(422);

        Map<String, Object> withCode = new HashMap<>(sale);
        withCode.put("discountCode", "ANY");
        assertThat(api.post("/api/v1/subscriptions", c.manager(), withCode).status()).isEqualTo(422);

        Api.Resp ok = api.post("/api/v1/subscriptions", c.manager(), sale);
        assertThat(ok.status()).as(ok.toString()).isEqualTo(201);
        assertThat(ok.body().get("priceBasis").asText()).isEqualTo("NEGOTIATED");
        assertThat(ok.body().get("price").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(ok.body().get("listPrice").decimalValue()).isEqualByComparingTo("2000.00");
        JsonNode inv = invoice(c.manager(), ok.body().get("invoiceId").asLong());
        assertThat(inv.at("/lines/0/amount").decimalValue()).isEqualByComparingTo("2000.00");
        assertThat(inv.at("/lines/1/description").asText()).isEqualTo("Negotiated price adjustment");
        assertThat(inv.at("/lines/1/amount").decimalValue()).isEqualByComparingTo("-500.00");
        assertThat(inv.get("taxAmount").decimalValue()).isEqualByComparingTo("270.00");
        assertThat(inv.get("totalAmount").decimalValue()).isEqualByComparingTo("1770.00");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where entity_type = 'SUBSCRIPTION' and entity_id = ?"
                + " and action = 'PRICE_OVERRIDDEN'", Long.class, ok.id())).isEqualTo(1);

        Api.Resp pay = payInvoice(c.manager(), inv.get("id").asLong(), unique("pay-"));
        assertThat(pay.status()).as(pay.toString()).isEqualTo(201);
        Api.Resp renewal = api.post("/api/v1/subscriptions/" + ok.id() + "/renew", c.desk(), Map.of());
        assertThat(renewal.status()).as(renewal.toString()).isEqualTo(201);
        assertThat(renewal.body().get("price").decimalValue()).as("kept on renewal").isEqualByComparingTo("1500.00");
        assertThat(renewal.body().get("priceBasis").asText()).isEqualTo("NEGOTIATED");

        Map<String, Object> adminSale = new HashMap<>(tooDeep);
        adminSale.put("memberId", createMember(c.desk(), c.branchId()));
        Api.Resp adminOk = api.post("/api/v1/subscriptions", c.admin(), adminSale);
        assertThat(adminOk.status()).as("admins are not limited").isEqualTo(201);
        assertThat(adminOk.body().get("keepPriceOnRenewal").asBoolean()).isTrue();
    }

    // ================================================================== GST documents

    private record Pdf(int status, byte[] body, HttpResponse<byte[]> raw) {
        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    private Pdf download(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<byte[]> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Pdf(r.statusCode(), r.body(), r);
    }

    private static String text(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            return new PdfTextExtractor(reader).getTextFromPage(1);
        } finally {
            reader.close();
        }
    }

    @Test
    void taxInvoiceAndCreditNotePdfsAreAuthorisedPerMemberAndBranch() throws Exception {
        Ctx c = setup();
        Api.Resp branch = api.get("/api/v1/branches/" + c.branchId(), c.admin());
        Map<String, Object> branchBody = new HashMap<>(Map.of("code", branch.body().get("code").asText(), "name", "GST branch",
                "addressLine1", "1 Street", "city", "Pune", "state", "Maharashtra", "country", "IN",
                "timezone", "Asia/Kolkata", "capacity", 100));
        branchBody.put("gstin", "27AAPFU0939F1ZX");
        assertThat(api.send("PUT", "/api/v1/branches/" + c.branchId(), c.admin(), branchBody, Map.of()).status())
                .as("bad GSTIN checksum").isEqualTo(400);
        branchBody.put("gstin", VALID_GSTIN.toLowerCase());
        Api.Resp updated = api.send("PUT", "/api/v1/branches/" + c.branchId(), c.admin(), branchBody, Map.of());
        assertThat(updated.status()).as(updated.toString()).isEqualTo(200);
        assertThat(updated.body().get("gstin").asText()).isEqualTo(VALID_GSTIN);
        assertThat(updated.body().get("gstStateCode").asText()).isEqualTo("27");

        Api.Resp self = api.post("/api/v1/auth/register", null, Map.of("email", unique("gst") + "@example.com",
                "password", "MemberPassw0rd", "firstName", "Asha", "lastName", "Rao", "phone", "+91 9111111111",
                "homeBranchId", c.branchId()));
        assertThat(self.status()).as(self.toString()).isEqualTo(201);
        String memberToken = self.body().get("accessToken").asText();
        long memberId = self.body().at("/user/memberId").asLong();
        long planId = plan(c.admin(), planBody("2000.00"));
        long subId = subscribeAndPay(c.desk(), memberId, planId);
        long invoiceId = api.get("/api/v1/subscriptions/" + subId, c.desk()).body().get("invoiceId").asLong();
        String invoiceNumber = invoice(c.desk(), invoiceId).get("invoiceNumber").asText();

        Pdf own = download("/api/v1/payments/invoices/" + invoiceId + "/pdf", memberToken);
        assertThat(own.status()).isEqualTo(200);
        assertThat(own.header("Content-Type")).startsWith("application/pdf");
        assertThat(own.header("Content-Disposition")).contains("attachment").contains(invoiceNumber + ".pdf");
        assertThat(own.header("Cache-Control")).contains("private").contains("no-store");
        String text = text(own.body());
        assertThat(text).contains("TAX INVOICE", invoiceNumber, "GSTIN: " + VALID_GSTIN, "999723", "CGST @ 9%", "SGST @ 9%",
                "Indian Rupees Two Thousand Three Hundred Sixty Only");

        long otherMember = createMember(c.desk(), c.branchId());
        long otherInvoice = subscribe(c.desk(), otherMember, planId).body().get("invoiceId").asLong();
        assertThat(download("/api/v1/payments/invoices/" + otherInvoice + "/pdf", memberToken).status())
                .as("another member's invoice").isEqualTo(403);
        assertThat(download("/api/v1/payments/invoices/" + invoiceId + "/pdf", null).status()).isEqualTo(401);
        assertThat(download("/api/v1/payments/invoices/" + invoiceId + "/pdf", c.desk()).status()).isEqualTo(200);
        long otherBranch = createBranch(c.admin());
        String outsider = login(createStaff(c.admin(), "RECEPTIONIST", otherBranch), STAFF_PASSWORD);
        assertThat(download("/api/v1/payments/invoices/" + invoiceId + "/pdf", outsider).status())
                .as("staff of another branch").isEqualTo(403);
        assertThat(download("/api/v1/payments/invoices/999999999/pdf", c.admin()).status()).isEqualTo(404);

        long paymentId = jdbc.queryForObject("select id from payments where invoice_id = ? and status = 'SUCCEEDED'",
                Long.class, invoiceId);
        Api.Resp refund = api.post("/api/v1/payments/" + paymentId + "/refunds", c.manager(),
                Map.of("amount", new BigDecimal("1180.00"), "reason", "Billing correction"));
        assertThat(refund.status()).as(refund.toString()).isEqualTo(201);
        String creditNote = refund.body().get("creditNoteNumber").asText();
        assertThat(creditNote).matches("CN-\\d{4}-\\d{6}");

        Pdf note = download("/api/v1/payments/refunds/" + refund.id() + "/credit-note.pdf", memberToken);
        assertThat(note.status()).isEqualTo(200);
        assertThat(note.header("Content-Disposition")).contains(creditNote + ".pdf");
        assertThat(text(note.body())).contains("CREDIT NOTE", creditNote, invoiceNumber, "Billing correction", "1,000.00",
                "CGST @ 9%", "90.00");
        assertThat(download("/api/v1/payments/refunds/" + refund.id() + "/credit-note.pdf", outsider).status())
                .isEqualTo(403);
        assertThat(text(download("/api/v1/payments/invoices/" + invoiceId + "/pdf", c.desk()).body()))
                .as("invoice lists the credit note").contains(creditNote);
    }
}
