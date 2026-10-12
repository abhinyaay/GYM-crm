package com.gymops.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.gymops.payment.autopay.AutopayJob;
import com.gymops.payment.gateway.SimulatedPaymentGateway;
import com.gymops.support.Api;
import com.gymops.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Payments v2 end to end over HTTP against PostgreSQL: hosted checkout with signature verification,
 * saved methods, webhooks, store credit, EMI and recurring billing (autopay) with dunning.
 */
class PaymentsV2IT extends IntegrationTestBase {

    @Autowired AutopayJob autopayJob;
    @Autowired SimulatedPaymentGateway simulator;

    private record Ctx(String admin, String desk, String manager, long branchId, long memberId, String deskEmail,
                       String managerEmail) {
    }

    private Ctx setup() {
        String admin = admin();
        long branchId = createBranch(admin);
        String deskEmail = createStaff(admin, "RECEPTIONIST", branchId);
        String managerEmail = createStaff(admin, "MANAGER", branchId);
        String desk = login(deskEmail, STAFF_PASSWORD);
        return new Ctx(admin, desk, login(managerEmail, STAFF_PASSWORD), branchId, createMember(desk, branchId), deskEmail,
                managerEmail);
    }

    /** Fresh tokens after the test clock jumps past the access-token lifetime. */
    private Ctx relogin(Ctx c) {
        return new Ctx(admin(), login(c.deskEmail(), STAFF_PASSWORD), login(c.managerEmail(), STAFF_PASSWORD), c.branchId(),
                c.memberId(), c.deskEmail(), c.managerEmail());
    }

    private Api.Resp checkout(String token, long invoiceId, String method, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("invoiceId", invoiceId, "method", method));
        body.putAll(extra);
        return api.post("/api/v1/payments/checkout", token, body, Map.of("Idempotency-Key", unique("chk-")));
    }

    private JsonNode simulate(String token, long intentId, Map<String, Object> outcome) {
        Api.Resp r = api.post("/api/v1/payments/checkout/" + intentId + "/simulate", token, outcome);
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r.body();
    }

    private Api.Resp confirm(String token, long intentId, JsonNode sim) {
        return api.post("/api/v1/payments/checkout/" + intentId + "/confirm", token, Map.of(
                "providerPaymentId", sim.get("providerPaymentId").asText(),
                "providerOrderId", sim.get("providerOrderId").asText(),
                "signature", sim.get("signature").asText()));
    }

    /** Subscribes and pays by UPI checkout with "save + renew automatically"; returns the subscription. */
    private JsonNode subscribeWithAutopay(Ctx c, long planId, boolean failFutureDebits) {
        Api.Resp sub = subscribe(c.desk(), c.memberId(), planId);
        Api.Resp start = checkout(c.desk(), sub.body().get("invoiceId").asLong(), "UPI",
                Map.of("saveMethod", true, "setupAutopay", true));
        assertThat(start.status()).as(start.toString()).isEqualTo(201);
        JsonNode sim = simulate(c.desk(), start.id("intentId"), Map.of("success", true, "vpa", "asha@okhdfc",
                "failFutureDebits", failFutureDebits));
        Api.Resp done = confirm(c.desk(), start.id("intentId"), sim);
        assertThat(done.status()).as(done.toString()).isEqualTo(200);
        assertThat(done.body().get("outcome").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.body().get("autopayEnabled").asBoolean()).isTrue();
        return api.get("/api/v1/subscriptions/" + sub.id(), c.desk()).body();
    }

    /** Debit attempts of one subscription (scoped, so leftovers from other tests in the shared DB don't matter). */
    private long attempts(long subscriptionId, String status) {
        return status == null
                ? jdbc.queryForObject("select count(*) from autopay_attempts where subscription_id = ?", Long.class, subscriptionId)
                : jdbc.queryForObject("select count(*) from autopay_attempts where subscription_id = ? and status = ?", Long.class,
                        subscriptionId, status);
    }

    private void advanceTo(LocalDate date) {
        long days = ChronoUnit.DAYS.between(LocalDate.now(clock), date);
        clock.advance(Duration.ofDays(days).plusHours(1));
    }

    @Test
    void hostedCheckoutVerifiesSignatureSavesMethodAndIsIdempotent() {
        Ctx c = setup();
        long planId = createPlan(c.admin(), "MONTHLY", "2000.00", "PRORATED", 3, Map.of());
        long invoiceId = subscribe(c.desk(), c.memberId(), planId).body().get("invoiceId").asLong();

        Api.Resp start = checkout(c.desk(), invoiceId, "CARD", Map.of("saveMethod", true));
        assertThat(start.status()).as(start.toString()).isEqualTo(201);
        assertThat(start.body().get("provider").asText()).isEqualTo("SIMULATED");
        assertThat(start.body().get("amountMinor").asLong()).isEqualTo(236000);   // 2000 + 18% GST, in paise
        long intentId = start.id("intentId");

        JsonNode sim = simulate(c.desk(), intentId, Map.of("success", true, "brand", "Mastercard", "last4", "5100"));
        Map<String, String> forged = Map.of("providerPaymentId", sim.get("providerPaymentId").asText(),
                "providerOrderId", sim.get("providerOrderId").asText(), "signature", "0".repeat(64));
        Api.Resp tampered = api.post("/api/v1/payments/checkout/" + intentId + "/confirm", c.desk(), forged);
        assertThat(tampered.status()).as("forged signature").isEqualTo(400);

        Api.Resp ok = confirm(c.desk(), intentId, sim);
        assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
        assertThat(ok.body().get("outcome").asText()).isEqualTo("SUCCEEDED");
        assertThat(ok.body().at("/payment/instrument").asText()).isEqualTo("Mastercard •••• 5100");
        assertThat(api.get("/api/v1/payments/invoices/" + invoiceId, c.desk()).body().get("status").asText()).isEqualTo("PAID");

        Api.Resp replay = confirm(c.desk(), intentId, sim);
        assertThat(replay.body().get("outcome").asText()).isEqualTo("ALREADY_PROCESSED");
        assertThat(jdbc.queryForObject("select count(*) from payments where invoice_id = ?", Long.class, invoiceId))
                .isEqualTo(1);

        JsonNode methods = api.get("/api/v1/members/" + c.memberId() + "/payment-methods", c.desk()).body();
        assertThat(methods).hasSize(1);
        assertThat(methods.get(0).get("label").asText()).isEqualTo("Mastercard •••• 5100");
        assertThat(methods.get(0).get("isDefault").asBoolean()).isTrue();
        assertThat(methods.get(0).get("mandateStatus").asText()).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("select count(*) from saved_payment_methods where provider_token like 'simtok_%'"
                + " and member_id = ?", Long.class, c.memberId())).isEqualTo(1);
    }

    @Test
    void failedCheckoutAttemptIsRecordedAndTheSameCheckoutCanBeRetried() {
        Ctx c = setup();
        long invoiceId = subscribe(c.desk(), c.memberId(),
                createPlan(c.admin(), "MONTHLY", "1500.00", "PRORATED", 3, Map.of())).body().get("invoiceId").asLong();
        long intentId = checkout(c.desk(), invoiceId, "NET_BANKING", Map.of()).id("intentId");

        Api.Resp failed = confirm(c.desk(), intentId, simulate(c.desk(), intentId,
                Map.of("success", false, "failureReason", "authentication_failed", "bank", "SBI")));
        assertThat(failed.body().get("outcome").asText()).isEqualTo("FAILED");
        assertThat(failed.body().at("/payment/failureReason").asText()).isEqualTo("authentication_failed");

        Api.Resp retried = confirm(c.desk(), intentId, simulate(c.desk(), intentId, Map.of("success", true, "bank", "SBI")));
        assertThat(retried.body().get("outcome").asText()).isEqualTo("SUCCEEDED");
        assertThat(retried.body().at("/payment/instrument").asText()).isEqualTo("Net banking SBI");
        assertThat(retried.body().at("/payment/method").asText()).isEqualTo("NET_BANKING");
    }

    @Test
    void secondCaptureForAnAlreadyPaidInvoiceIsRefundedAutomatically() {
        Ctx c = setup();
        long invoiceId = subscribe(c.desk(), c.memberId(),
                createPlan(c.admin(), "MONTHLY", "1500.00", "PRORATED", 3, Map.of())).body().get("invoiceId").asLong();
        long first = checkout(c.desk(), invoiceId, "UPI", Map.of()).id("intentId");
        long second = checkout(c.desk(), invoiceId, "CARD", Map.of()).id("intentId");
        JsonNode simFirst = simulate(c.desk(), first, Map.of("success", true));
        JsonNode simSecond = simulate(c.desk(), second, Map.of("success", true));

        assertThat(confirm(c.desk(), first, simFirst).body().get("outcome").asText()).isEqualTo("SUCCEEDED");
        Api.Resp dup = confirm(c.desk(), second, simSecond);
        assertThat(dup.body().get("outcome").asText()).isEqualTo("REFUNDED_DUPLICATE");
        assertThat(dup.body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select count(*) from payments where invoice_id = ? and status = 'SUCCEEDED'",
                Long.class, invoiceId)).isEqualTo(1);
    }

    @Test
    void webhookSettlesCheckoutOnceAndRejectsBadSignatures() {
        Ctx c = setup();
        long invoiceId = subscribe(c.desk(), c.memberId(),
                createPlan(c.admin(), "MONTHLY", "1000.00", "PRORATED", 3, Map.of())).body().get("invoiceId").asLong();
        Api.Resp start = checkout(c.desk(), invoiceId, "UPI", Map.of());
        JsonNode sim = simulate(c.desk(), start.id("intentId"), Map.of("success", true));
        String body = """
                {"event":"payment.captured","payload":{"payment":{"entity":{"id":"%s","order_id":"%s",
                "status":"captured","method":"upi","amount":%d,"vpa":"asha@okhdfc"}}}}"""
                .formatted(sim.get("providerPaymentId").asText(), sim.get("providerOrderId").asText(),
                        start.body().get("amountMinor").asLong());

        Api.Resp forged = api.send("POST", "/api/v1/webhooks/simulated", null, new Api.Raw(body),
                Map.of("X-Razorpay-Signature", "f".repeat(64), "X-Razorpay-Event-Id", "evt_1"));
        assertThat(forged.status()).isEqualTo(400);

        Map<String, String> headers = Map.of("X-Razorpay-Signature", simulator.signWebhook(body), "X-Razorpay-Event-Id",
                unique("evt_"));
        Api.Resp hook = api.send("POST", "/api/v1/webhooks/simulated", null, new Api.Raw(body), headers);
        assertThat(hook.status()).as(hook.toString()).isEqualTo(200);
        assertThat(hook.body().get("status").asText()).isEqualTo("PROCESSED");
        assertThat(api.get("/api/v1/payments/invoices/" + invoiceId, c.desk()).body().get("status").asText()).isEqualTo("PAID");

        assertThat(api.send("POST", "/api/v1/webhooks/simulated", null, new Api.Raw(body), headers).body().get("status").asText())
                .isEqualTo("DUPLICATE");
        assertThat(confirm(c.desk(), start.id("intentId"), sim).body().get("outcome").asText())
                .as("browser callback after the webhook").isEqualTo("ALREADY_PROCESSED");
        assertThat(jdbc.queryForObject("select count(*) from payments where invoice_id = ?", Long.class, invoiceId))
                .isEqualTo(1);
    }

    @Test
    void autopayRenewsBeforeTheTermEndsAndCarriesOverToTheNextTerm() {
        Ctx c = setup();
        long planId = createPlan(c.admin(), "MONTHLY", "2000.00", "PRORATED", 3, Map.of());
        JsonNode sub = subscribeWithAutopay(c, planId, false);
        assertThat(sub.get("autoRenew").asBoolean()).isTrue();
        LocalDate end = LocalDate.parse(sub.get("endDate").asText());
        JsonNode method = api.get("/api/v1/members/" + c.memberId() + "/payment-methods", c.desk()).body().get(0);
        assertThat(method.get("mandateStatus").asText()).isEqualTo("ACTIVE");
        assertThat(method.get("label").asText()).isEqualTo("UPI as***@okhdfc");

        long subId = sub.get("id").asLong();
        autopayJob.run();
        assertThat(attempts(subId, null)).as("too early").isZero();
        advanceTo(end.minusDays(3));
        autopayJob.run();
        assertThat(attempts(subId, "SCHEDULED")).isEqualTo(1);
        assertThat(attempts(subId, null)).as("pre-debit notice must elapse first").isEqualTo(1);
        autopayJob.run();
        assertThat(attempts(subId, null)).as("idempotent").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notifications where member_id = ? and type = 'AUTOPAY_SCHEDULED'",
                Long.class, c.memberId())).isPositive();

        clock.advance(Duration.ofHours(25));
        autopayJob.run();
        assertThat(attempts(subId, "SUCCEEDED")).isEqualTo(1);
        c = relogin(c);

        JsonNode attempts = api.get("/api/v1/autopay/attempts?memberId=" + c.memberId(), c.desk()).body().get("content");
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).get("status").asText()).isEqualTo("SUCCEEDED");
        long renewalId = attempts.get(0).get("renewalSubscriptionId").asLong();
        JsonNode renewal = api.get("/api/v1/subscriptions/" + renewalId, c.desk()).body();
        assertThat(renewal.get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(renewal.get("startDate").asText()).isEqualTo(end.plusDays(1).toString());
        assertThat(renewal.get("autoRenew").asBoolean()).as("next term keeps autopay").isTrue();
        assertThat(api.get("/api/v1/subscriptions/" + sub.get("id").asLong(), c.desk()).body().get("autoRenew").asBoolean())
                .isFalse();
        assertThat(jdbc.queryForObject("select autopay from payments where id = ?", Boolean.class,
                attempts.get(0).get("paymentId").asLong())).isTrue();
    }

    @Test
    void failedAutopayDebitsAreRetriedThenAutopayIsSwitchedOff() {
        Ctx c = setup();
        long planId = createPlan(c.admin(), "MONTHLY", "2000.00", "PRORATED", 3, Map.of());
        JsonNode sub = subscribeWithAutopay(c, planId, true);   // token whose later debits are declined
        LocalDate end = LocalDate.parse(sub.get("endDate").asText());
        advanceTo(end.minusDays(3));
        autopayJob.run();

        long subId = sub.get("id").asLong();
        for (int attempt = 1; attempt <= 4; attempt++) {
            clock.advance(Duration.ofDays(4));
            autopayJob.run();
            assertThat(attempts(subId, "FAILED")).as("attempt " + attempt).isEqualTo(attempt);
            if (attempt < 4) {
                runLifecycle();   // the renewal must survive the stale-unpaid sweep while retries are pending
            }
        }
        c = relogin(c);
        JsonNode attempts = api.get("/api/v1/autopay/attempts?subscriptionId=" + sub.get("id").asLong() + "&sort=scheduledAt,asc",
                c.desk()).body().get("content");
        assertThat(attempts).hasSize(4);
        assertThat(attempts.findValuesAsText("status")).containsOnly("FAILED");
        assertThat(attempts.findValuesAsText("failureReason")).containsOnly("card_declined");
        assertThat(api.get("/api/v1/subscriptions/" + sub.get("id").asLong(), c.desk()).body().get("autoRenew").asBoolean())
                .isFalse();
        assertThat(jdbc.queryForObject("select count(*) from notifications where member_id = ? and type = 'AUTOPAY_FAILED'"
                + " and channel = 'EMAIL'", Long.class, c.memberId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from notifications where member_id = ? and type = 'AUTOPAY_DISABLED'"
                + " and channel = 'EMAIL'", Long.class, c.memberId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notifications where member_id = ? and type = 'PAYMENT_FAILED'",
                Long.class, c.memberId())).as("no duplicate generic failure notices").isZero();

        // a manager can still retry the last failed debit; it is declined again
        Api.Resp retry = api.post("/api/v1/autopay/attempts/" + attempts.get(3).get("id").asLong() + "/retry", c.manager(), null);
        assertThat(retry.status()).as(retry.toString()).isEqualTo(200);
        assertThat(retry.body().get("status").asText()).isEqualTo("FAILED");
        assertThat(retry.body().get("attemptNo").asInt()).isEqualTo(5);
    }

    @Test
    void removingTheAutopayMethodSwitchesAutopayOffAndCancelsScheduledDebits() {
        Ctx c = setup();
        JsonNode sub = subscribeWithAutopay(c, createPlan(c.admin(), "MONTHLY", "2000.00", "PRORATED", 3, Map.of()), false);
        advanceTo(LocalDate.parse(sub.get("endDate").asText()).minusDays(3));
        autopayJob.run();
        c = relogin(c);
        long methodId = api.get("/api/v1/members/" + c.memberId() + "/payment-methods", c.desk()).body().get(0).get("id").asLong();

        assertThat(api.send("DELETE", "/api/v1/members/" + c.memberId() + "/payment-methods/" + methodId, c.desk(), null,
                Map.of()).status()).isEqualTo(204);

        assertThat(api.get("/api/v1/subscriptions/" + sub.get("id").asLong(), c.desk()).body().get("autoRenew").asBoolean())
                .isFalse();
        assertThat(jdbc.queryForObject("select status from autopay_attempts where subscription_id = ?", String.class,
                sub.get("id").asLong())).isEqualTo("CANCELLED");
        assertThat(api.get("/api/v1/members/" + c.memberId() + "/payment-methods", c.desk()).body()).isEmpty();
    }

    @Test
    void savedMethodPaysInOneClickAndAutopayCanBeToggled() {
        Ctx c = setup();
        long planId = createPlan(c.admin(), "MONTHLY", "2000.00", "PRORATED", 3, Map.of());
        JsonNode sub = subscribeWithAutopay(c, planId, false);
        long methodId = api.get("/api/v1/members/" + c.memberId() + "/payment-methods", c.desk()).body().get(0).get("id").asLong();

        Api.Resp off = api.send("PUT", "/api/v1/subscriptions/" + sub.get("id").asLong() + "/autopay", c.desk(),
                Map.of("enabled", false), Map.of());
        assertThat(off.body().get("autoRenew").asBoolean()).isFalse();
        Api.Resp on = api.send("PUT", "/api/v1/subscriptions/" + sub.get("id").asLong() + "/autopay", c.desk(),
                Map.of("enabled", true, "paymentMethodId", methodId), Map.of());
        assertThat(on.status()).as(on.toString()).isEqualTo(200);
        assertThat(on.body().get("autoRenew").asBoolean()).isTrue();

        long renewalInvoice = api.post("/api/v1/subscriptions/" + sub.get("id").asLong() + "/renew", c.desk(), Map.of())
                .body().get("invoiceId").asLong();
        BigDecimal total = api.get("/api/v1/payments/invoices/" + renewalInvoice, c.desk()).body().get("totalAmount").decimalValue();
        Api.Resp oneClick = api.post("/api/v1/payments", c.desk(), Map.of("invoiceId", renewalInvoice, "amount", total,
                "method", "UPI", "paymentMethodId", methodId), Map.of("Idempotency-Key", unique("oneclick-")));
        assertThat(oneClick.status()).as(oneClick.toString()).isEqualTo(201);
        assertThat(oneClick.body().get("paymentMethodId").asLong()).isEqualTo(methodId);
        assertThat(oneClick.body().get("instrument").asText()).isEqualTo("UPI as***@okhdfc");
    }

    @Test
    void storeCreditCanBeIssuedSpentAndRefundedInto() {
        Ctx c = setup();
        long planId = createPlan(c.admin(), "MONTHLY", "1000.00", "PRORATED", 3, Map.of());
        assertThat(api.post("/api/v1/members/" + c.memberId() + "/credit", c.desk(),
                Map.of("amount", "500.00", "reason", "Goodwill")).status()).as("receptionist cannot issue").isEqualTo(403);
        Api.Resp issued = api.post("/api/v1/members/" + c.memberId() + "/credit", c.manager(),
                Map.of("amount", "1500.00", "reason", "Goodwill for AC outage"));
        assertThat(issued.status()).as(issued.toString()).isEqualTo(200);
        assertThat(issued.body().get("balance").decimalValue()).isEqualByComparingTo("1500.00");

        long invoiceId = subscribe(c.desk(), c.memberId(), planId).body().get("invoiceId").asLong();
        Api.Resp paid = api.post("/api/v1/payments", c.desk(), Map.of("invoiceId", invoiceId, "amount", "1180.00",
                "method", "STORE_CREDIT"), Map.of("Idempotency-Key", unique("credit-")));
        assertThat(paid.status()).as(paid.toString()).isEqualTo(201);
        assertThat(paid.body().get("provider").asText()).isEqualTo("INTERNAL");

        JsonNode account = api.get("/api/v1/members/" + c.memberId() + "/credit", c.desk()).body();
        assertThat(account.get("balance").decimalValue()).isEqualByComparingTo("320.00");
        assertThat(account.get("entries").findValuesAsText("entryType")).containsExactly("PAYMENT", "ISSUED");

        Api.Resp refund = api.post("/api/v1/payments/" + paid.id() + "/refunds", c.manager(),
                Map.of("amount", "200.00", "reason", "Partial goodwill"), Map.of("Idempotency-Key", unique("rf-")));
        assertThat(refund.status()).as(refund.toString()).isEqualTo(201);
        assertThat(refund.body().get("destination").asText()).as("credit payments refund to credit").isEqualTo("STORE_CREDIT");
        assertThat(api.get("/api/v1/members/" + c.memberId() + "/credit", c.desk()).body().get("balance").decimalValue())
                .isEqualByComparingTo("520.00");

        long second = subscribeAndPayInvoiceLater(c, planId);
        Api.Resp insufficient = api.post("/api/v1/payments", c.desk(), Map.of("invoiceId", second, "amount", "1180.00",
                "method", "STORE_CREDIT"), Map.of("Idempotency-Key", unique("credit-")));
        assertThat(insufficient.status()).isEqualTo(422);
    }

    private long subscribeAndPayInvoiceLater(Ctx c, long planId) {
        long otherMember = createMember(c.desk(), c.branchId());
        return subscribe(c.desk(), otherMember, planId).body().get("invoiceId").asLong();
    }

    @Test
    void emiNeedsAQualifyingAmountAndTenure() {
        Ctx c = setup();
        long cheap = subscribe(c.desk(), c.memberId(), createPlan(c.admin(), "MONTHLY", "1000.00", "PRORATED", 3, Map.of()))
                .body().get("invoiceId").asLong();
        assertThat(checkout(c.desk(), cheap, "EMI", Map.of("emiTenureMonths", 6)).status()).isEqualTo(422);

        long other = createMember(c.desk(), c.branchId());
        long annual = subscribe(c.desk(), other, createPlan(c.admin(), "ANNUAL", "18000.00", "PRORATED", 3, Map.of()))
                .body().get("invoiceId").asLong();
        assertThat(checkout(c.desk(), annual, "EMI", Map.of("emiTenureMonths", 5)).status()).isEqualTo(422);
        Api.Resp start = checkout(c.desk(), annual, "EMI", Map.of("emiTenureMonths", 6));
        assertThat(start.status()).as(start.toString()).isEqualTo(201);
        Api.Resp done = confirm(c.desk(), start.id("intentId"), simulate(c.desk(), start.id("intentId"),
                Map.of("success", true, "brand", "Visa", "last4", "1111")));
        assertThat(done.body().at("/payment/method").asText()).isEqualTo("EMI");
        assertThat(done.body().at("/payment/emiTenureMonths").asInt()).isEqualTo(6);
        assertThat(api.post("/api/v1/payments", c.desk(), Map.of("invoiceId", cheap, "amount", "1180.00", "method", "WALLET"),
                Map.of("Idempotency-Key", unique("w-"))).status()).as("wallet needs checkout").isEqualTo(422);
        assertThat(api.get("/api/v1/payments/config", c.desk()).body().get("emiTenures").toString()).isEqualTo("[3,6,9,12]");
    }

    @Test
    void membersCannotSelfSubscribeToFreePlansButStaffCan() {
        Ctx c = setup();
        long free = createPlan(c.admin(), "CUSTOM", "0.00", "NON_REFUNDABLE", 0, Map.of("customDurationDays", 3));
        Api.Resp signup = api.post("/api/v1/auth/register", null, Map.of("email", unique("self") + "@example.com",
                "password", "SelfServ1ce!x", "firstName", "Self", "lastName", "Trial", "phone", "+91 9111111111",
                "homeBranchId", c.branchId()));
        assertThat(signup.status()).as(signup.toString()).isEqualTo(201);
        String member = signup.body().get("accessToken").asText();
        long memberId = api.get("/api/v1/members/me", member).id();

        Api.Resp self = api.post("/api/v1/subscriptions", member, Map.of("memberId", memberId, "planId", free));
        assertThat(self.status()).as(self.toString()).isEqualTo(422);
        assertThat(api.post("/api/v1/subscriptions", c.desk(), Map.of("memberId", memberId, "planId", free)).status())
                .as("staff grant the trial").isEqualTo(201);
    }
}
