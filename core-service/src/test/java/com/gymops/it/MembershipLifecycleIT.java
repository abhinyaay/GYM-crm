package com.gymops.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymops.support.Api;
import com.gymops.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** End-to-end business flows through the HTTP API against PostgreSQL + Redis. */
class MembershipLifecycleIT extends IntegrationTestBase {

    private long branchId;
    private String desk;

    @BeforeEach
    void setUp() {
        String admin = admin();
        branchId = createBranch(admin);
        desk = login(createStaff(admin, "RECEPTIONIST", branchId), STAFF_PASSWORD);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    @Test
    void subscribePayCheckInAndRejectDuplicateCheckIn() {
        long planId = createPlan(admin(), "MONTHLY", "3000.00", "PRORATED", 3, Map.of("joiningFee", "500.00"));
        long memberId = createMember(desk, branchId);

        Api.Resp sub = subscribe(desk, memberId, planId);
        assertThat(sub.body().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        Api.Resp invoice = api.get("/api/v1/payments/invoices/" + sub.body().get("invoiceId").asLong(), desk);
        // (3000 + 500 joining) * 1.18 GST
        assertThat(invoice.body().get("totalAmount").decimalValue()).isEqualByComparingTo("4130.00");

        assertThat(payInvoice(desk, invoice.id(), unique("k-")).status()).isEqualTo(201);
        assertThat(api.get("/api/v1/subscriptions/" + sub.id(), desk).body().get("status").asText()).isEqualTo("ACTIVE");

        Map<String, Object> checkIn = Map.of("memberId", memberId, "branchId", branchId);
        assertThat(api.post("/api/v1/attendance/check-in", desk, checkIn).status()).isEqualTo(201);
        Api.Resp dup = api.post("/api/v1/attendance/check-in", desk, checkIn);
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body().get("code").asText()).isEqualTo("DUPLICATE_CHECK_IN");

        Api.Resp profile = api.get("/api/v1/members/" + memberId, desk);
        assertThat(profile.body().at("/membership/state").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void expiredMembershipPassesThroughGraceThenBlocksCheckIn() {
        long planId = createPlan(admin(), "MONTHLY", "3000.00", "NON_REFUNDABLE", 3, Map.of());
        long memberId = createMember(desk, branchId);
        long subId = subscribeAndPay(desk, memberId, planId);
        LocalDate end = LocalDate.parse(api.get("/api/v1/subscriptions/" + subId, desk).body().get("endDate").asText());

        // day after the end date: GRACE, member can still train
        clock.advance(Duration.ofDays(end.toEpochDay() - today().toEpochDay() + 1));
        runLifecycle();
        String deskNow = login(createStaff(admin(), "RECEPTIONIST", branchId), STAFF_PASSWORD);
        assertThat(api.get("/api/v1/subscriptions/" + subId, deskNow).body().get("status").asText()).isEqualTo("GRACE");
        Map<String, Object> checkIn = Map.of("memberId", memberId, "branchId", branchId);
        assertThat(api.post("/api/v1/attendance/check-in", deskNow, checkIn).status()).isEqualTo(201);
        api.post("/api/v1/attendance/check-out", deskNow, Map.of("memberId", memberId));

        // grace period over: EXPIRED, check-in refused
        clock.advance(Duration.ofDays(3));
        runLifecycle();
        String deskLater = login(createStaff(admin(), "RECEPTIONIST", branchId), STAFF_PASSWORD);
        assertThat(api.get("/api/v1/subscriptions/" + subId, deskLater).body().get("status").asText()).isEqualTo("EXPIRED");
        Api.Resp denied = api.post("/api/v1/attendance/check-in", deskLater, checkIn);
        assertThat(denied.status()).isEqualTo(422);
        assertThat(denied.body().get("code").asText()).isEqualTo("MEMBERSHIP_NOT_ACTIVE");

        // renewing an expired membership starts today
        Api.Resp renewal = api.post("/api/v1/subscriptions/" + subId + "/renew", deskLater, Map.of());
        assertThat(renewal.status()).isEqualTo(201);
        assertThat(renewal.body().get("startDate").asText()).isEqualTo(today().toString());
        Long expiredNotifications = jdbc.queryForObject(
                "select count(*) from notifications where member_id = ? and type = 'MEMBERSHIP_EXPIRED'", Long.class, memberId);
        assertThat(expiredNotifications).isGreaterThan(0);
    }

    @Test
    void earlyRenewalIsScheduledAndStartsWhenCurrentTermEnds() {
        long planId = createPlan(admin(), "MONTHLY", "2000.00", "PRORATED", 5, Map.of());
        long memberId = createMember(desk, branchId);
        long subId = subscribeAndPay(desk, memberId, planId);
        Api.Resp renewal = api.post("/api/v1/subscriptions/" + subId + "/renew", desk, Map.of());
        assertThat(payInvoice(desk, renewal.body().get("invoiceId").asLong(), unique("k-")).status()).isEqualTo(201);
        assertThat(api.get("/api/v1/subscriptions/" + renewal.id(), desk).body().get("status").asText()).isEqualTo("SCHEDULED");

        LocalDate start = LocalDate.parse(renewal.body().get("startDate").asText());
        clock.advance(Duration.ofDays(start.toEpochDay() - today().toEpochDay()));
        runLifecycle();
        String deskNow = login(createStaff(admin(), "RECEPTIONIST", branchId), STAFF_PASSWORD);
        assertThat(api.get("/api/v1/subscriptions/" + renewal.id(), deskNow).body().get("status").asText()).isEqualTo("ACTIVE");
        // renewed membership skips grace and goes straight to EXPIRED
        assertThat(api.get("/api/v1/subscriptions/" + subId, deskNow).body().get("status").asText()).isEqualTo("EXPIRED");
    }

    @Test
    void freezeShiftsQueuedRenewalAndResumeShiftsItBack() {
        long planId = createPlan(admin(), "MONTHLY", "2000.00", "PRORATED", 0, Map.of());
        long memberId = createMember(desk, branchId);
        long subId = subscribeAndPay(desk, memberId, planId);
        Api.Resp renewal = api.post("/api/v1/subscriptions/" + subId + "/renew", desk, Map.of());
        payInvoice(desk, renewal.body().get("invoiceId").asLong(), unique("k-"));
        LocalDate renewalStart = LocalDate.parse(renewal.body().get("startDate").asText());

        assertThat(api.post("/api/v1/subscriptions/" + subId + "/freeze", desk, Map.of("days", 10)).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/subscriptions/" + renewal.id(), desk).body().get("startDate").asText())
                .isEqualTo(renewalStart.plusDays(10).toString());

        assertThat(api.post("/api/v1/subscriptions/" + subId + "/resume", desk, null).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/subscriptions/" + renewal.id(), desk).body().get("startDate").asText())
                .isEqualTo(renewalStart.toString());
    }

    @Test
    void proratedCancellationRefundsUnusedValueMinusFee() {
        long planId = createPlan(admin(), "CUSTOM", "3000.00", "PRORATED", 0,
                Map.of("customDurationDays", 30, "cancellationFee", "100.00"));
        long memberId = createMember(desk, branchId);
        long subId = subscribeAndPay(desk, memberId, planId);
        String manager = login(createStaff(admin(), "MANAGER", branchId), STAFF_PASSWORD);

        clock.advance(Duration.ofDays(9));   // day 10 of 30 consumed -> 20 unused days
        manager = login(createStaff(admin(), "MANAGER", branchId), STAFF_PASSWORD);
        Api.Resp cancel = api.post("/api/v1/subscriptions/" + subId + "/cancel", manager, Map.of("reason", "Relocating"));
        assertThat(cancel.status()).as(cancel.toString()).isEqualTo(200);
        // paid 3540.00 (3000 + 18% GST); 3540 * 20 / 30 = 2360.00 - 100 fee = 2260.00
        assertThat(cancel.body().get("refundAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("2260.00"));
        Map<String, Object> payment = jdbc.queryForMap(
                "select p.status, p.refunded_amount from payments p join invoices i on i.id = p.invoice_id "
                        + "where i.subscription_id = ? and p.status <> 'FAILED'", subId);
        assertThat(payment.get("status")).isEqualTo("PARTIALLY_REFUNDED");
        assertThat((BigDecimal) payment.get("refunded_amount")).isEqualByComparingTo("2260.00");
        // cancelling again is an invalid transition and never refunds twice
        assertThat(api.post("/api/v1/subscriptions/" + subId + "/cancel", manager, Map.of("reason", "again")).status())
                .isEqualTo(409);
    }

    @Test
    void overlappingNewSubscriptionIsRejected() {
        long planId = createPlan(admin(), "MONTHLY", "2000.00", "PRORATED", 0, Map.of());
        long memberId = createMember(desk, branchId);
        subscribeAndPay(desk, memberId, planId);
        Api.Resp overlap = api.post("/api/v1/subscriptions", desk, Map.of("memberId", memberId, "planId", planId));
        assertThat(overlap.status()).isEqualTo(409);
        assertThat(overlap.body().get("code").asText()).isEqualTo("SUBSCRIPTION_OVERLAP");
    }

    @Test
    void dashboardDegradesGracefullyWhenAnalyticsIsDown() {
        Api.Resp dashboard = api.get("/api/v1/dashboard/admin?branchId=" + branchId, admin());
        assertThat(dashboard.status()).isEqualTo(200);
        assertThat(dashboard.body().get("degraded").toString()).contains("memberChurn");
        assertThat(dashboard.body().get("memberChurn").isNull()).isTrue();
    }
}
