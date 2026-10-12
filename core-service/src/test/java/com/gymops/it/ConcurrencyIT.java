package com.gymops.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymops.support.Api;
import com.gymops.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fires genuinely concurrent HTTP requests at the same resource and asserts the invariants hold:
 * no double charge, one open visit, one winner for conflicting subscription changes.
 */
class ConcurrencyIT extends IntegrationTestBase {

    private static final int THREADS = 12;

    private long branchId;
    private String desk;
    private long planId;
    private long premiumPlanId;

    @BeforeEach
    void setUp() {
        String admin = admin();
        branchId = createBranch(admin);
        desk = login(createStaff(admin, "RECEPTIONIST", branchId), STAFF_PASSWORD);
        planId = createPlan(admin, "MONTHLY", "2500.00", "PRORATED", 3, Map.of());
        premiumPlanId = createPlan(admin, "ANNUAL", "24000.00", "PRORATED", 3, Map.of());
    }

    private List<Api.Resp> concurrently(List<Callable<Api.Resp>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Api.Resp>> futures = new ArrayList<>();
        for (Callable<Api.Resp> call : calls) {
            futures.add(pool.submit(() -> {
                start.await();
                return call.call();
            }));
        }
        start.countDown();
        List<Api.Resp> results = new ArrayList<>();
        for (Future<Api.Resp> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }

    private static long count(List<Api.Resp> results, int status) {
        return results.stream().filter(r -> r.status() == status).count();
    }

    @Test
    void retriesWithSameIdempotencyKeyChargeExactlyOnce() throws Exception {
        long memberId = createMember(desk, branchId);
        long invoiceId = subscribe(desk, memberId, planId).body().get("invoiceId").asLong();
        String key = unique("same-key-");

        List<Callable<Api.Resp>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> payInvoice(desk, invoiceId, key));
        }
        List<Api.Resp> results = concurrently(calls);

        assertThat(count(results, 201)).isEqualTo(1);
        assertThat(count(results, 200)).isEqualTo(THREADS - 1);   // idempotent replays
        assertThat(results.stream().map(r -> r.body().get("id").asLong()).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from payments where invoice_id = ?", Long.class, invoiceId)).isEqualTo(1);
    }

    @Test
    void differentKeysForSameInvoiceSettleItOnlyOnce() throws Exception {
        long memberId = createMember(desk, branchId);
        long invoiceId = subscribe(desk, memberId, planId).body().get("invoiceId").asLong();

        List<Callable<Api.Resp>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String key = unique("key-" + i + "-");
            calls.add(() -> payInvoice(desk, invoiceId, key));
        }
        List<Api.Resp> results = concurrently(calls);

        assertThat(count(results, 201)).isEqualTo(1);
        assertThat(count(results, 409)).isEqualTo(THREADS - 1);
        assertThat(jdbc.queryForObject("select count(*) from payments where invoice_id = ? and status = 'SUCCEEDED'",
                Long.class, invoiceId)).isEqualTo(1);
    }

    @Test
    void simultaneousCheckInsCreateOneVisit() throws Exception {
        long memberId = createMember(desk, branchId);
        subscribeAndPay(desk, memberId, planId);

        List<Callable<Api.Resp>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> api.post("/api/v1/attendance/check-in", desk, Map.of("memberId", memberId, "branchId", branchId)));
        }
        List<Api.Resp> results = concurrently(calls);

        assertThat(count(results, 201)).isEqualTo(1);
        assertThat(count(results, 409)).isEqualTo(THREADS - 1);
        assertThat(jdbc.queryForObject("select count(*) from attendance where member_id = ? and check_out_time is null",
                Long.class, memberId)).isEqualTo(1);
    }

    @Test
    void conflictingRenewAndUpgradeRequestsHaveExactlyOneWinner() throws Exception {
        long memberId = createMember(desk, branchId);
        long subId = subscribeAndPay(desk, memberId, planId);

        List<Callable<Api.Resp>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            if (i % 2 == 0) {
                calls.add(() -> api.post("/api/v1/subscriptions/" + subId + "/renew", desk, Map.of()));
            } else {
                calls.add(() -> api.post("/api/v1/subscriptions/" + subId + "/change-plan", desk,
                        Map.of("newPlanId", premiumPlanId)));
            }
        }
        List<Api.Resp> results = concurrently(calls);

        assertThat(count(results, 201)).isEqualTo(1);
        assertThat(count(results, 409)).isEqualTo(THREADS - 1);
        assertThat(jdbc.queryForObject("select count(*) from subscriptions where previous_subscription_id = ?",
                Long.class, subId)).isEqualTo(1);
    }

    @Test
    void concurrentFreezeRequestsApplyOnce() throws Exception {
        long memberId = createMember(desk, branchId);
        long subId = subscribeAndPay(desk, memberId, planId);
        String originalEnd = api.get("/api/v1/subscriptions/" + subId, desk).body().get("endDate").asText();

        List<Callable<Api.Resp>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> api.post("/api/v1/subscriptions/" + subId + "/freeze", desk, Map.of("days", 5)));
        }
        List<Api.Resp> results = concurrently(calls);

        assertThat(count(results, 200)).isEqualTo(1);
        Api.Resp sub = api.get("/api/v1/subscriptions/" + subId, desk);
        assertThat(sub.body().get("freezeDaysUsed").asInt()).isEqualTo(5);
        assertThat(sub.body().get("endDate").asText()).isEqualTo(
                java.time.LocalDate.parse(originalEnd).plusDays(5).toString());
    }
}
