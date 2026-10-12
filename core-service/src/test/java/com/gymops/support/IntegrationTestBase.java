package com.gymops.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Full application on a random port against real PostgreSQL and Redis containers. All tests share
 * one Spring context and one database, so every test creates its own uniquely-named data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.security.jwt.secret=integration-test-secret-integration-test-0123456789",
        "app.bootstrap.admin-email=" + IntegrationTestBase.ADMIN_EMAIL,
        "app.bootstrap.admin-password=" + IntegrationTestBase.ADMIN_PASSWORD,
        "app.scheduling.enabled=false",
        "app.rate-limit.enabled=false",
        "app.analytics.base-url=http://127.0.0.1:1",
        "app.bootstrap.demo-data=false"})
@Import(IntegrationTestBase.TestClockConfig.class)
public abstract class IntegrationTestBase {

    public static final String ADMIN_EMAIL = "it-admin@example.com";
    public static final String ADMIN_PASSWORD = "IntegrationAdmin123";
    protected static final String STAFF_PASSWORD = "StaffPassw0rd1";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        Containers.register(registry);
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(ZoneId.of("Asia/Kolkata"));
        }
    }

    @LocalServerPort protected int port;
    @Autowired protected MutableClock clock;
    @Autowired protected JdbcTemplate jdbc;
    protected Api api;

    @BeforeEach
    void initApi() {
        api = new Api(port);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    protected static String unique(String prefix) {
        return prefix + SEQ.incrementAndGet() + UUID.randomUUID().toString().substring(0, 6);
    }

    protected String login(String email, String password) {
        Api.Resp r = api.post("/api/v1/auth/login", null, Map.of("email", email, "password", password));
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r.body().get("accessToken").asText();
    }

    protected String admin() {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    protected long createBranch(String admin) {
        String code = unique("B").toUpperCase().replaceAll("[^A-Z0-9]", "");
        code = code.substring(0, Math.min(code.length(), 18));
        Api.Resp r = api.post("/api/v1/branches", admin, Map.of("code", code, "name", "Branch " + code,
                "addressLine1", "1 Street", "city", "Pune", "country", "IN", "timezone", "Asia/Kolkata", "capacity", 100));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.id();
    }

    /** Creates a staff user with one role, assigned to the given branches; returns the email. */
    protected String createStaff(String admin, String role, long... branchIds) {
        String email = unique(role.toLowerCase()) + "@example.com";
        Api.Resp r = api.post("/api/v1/users", admin, Map.of("email", email, "initialPassword", STAFF_PASSWORD,
                "fullName", "Staff " + role, "roles", List.of(role),
                "branchIds", java.util.Arrays.stream(branchIds).boxed().toList()));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return email;
    }

    /** Creates and activates a plan. */
    protected long createPlan(String admin, String period, String price, String policy, int grace, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("code", unique("P").replaceAll("[^A-Za-z0-9]", ""),
                "name", "Plan", "billingPeriod", period, "price", price, "cancellationPolicy", policy,
                "gracePeriodDays", grace, "freezeAllowed", true, "minFreezeDays", 1, "maxFreezeDays", 30,
                "maxFreezeCount", 2));
        body.putAll(extra);
        Api.Resp r = api.post("/api/v1/plans", admin, body);
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        Api.Resp activated = api.patch("/api/v1/plans/" + r.id() + "/status", admin, Map.of("status", "ACTIVE"));
        assertThat(activated.status()).as(activated.toString()).isEqualTo(200);
        return r.id();
    }

    protected long createMember(String token, long branchId) {
        Api.Resp r = api.post("/api/v1/members", token, Map.of("homeBranchId", branchId, "firstName", "Test",
                "lastName", "Member", "email", unique("member") + "@example.com", "phone", "+91 9000000000"));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.id();
    }

    /** Creates a subscription and returns the response body (includes invoiceId). */
    protected Api.Resp subscribe(String token, long memberId, long planId) {
        Api.Resp r = api.post("/api/v1/subscriptions", token, Map.of("memberId", memberId, "planId", planId));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r;
    }

    protected Api.Resp payInvoice(String token, long invoiceId, String key) {
        Api.Resp invoice = api.get("/api/v1/payments/invoices/" + invoiceId, token);
        return api.post("/api/v1/payments", token, Map.of("invoiceId", invoiceId,
                "amount", invoice.body().get("totalAmount").decimalValue(), "method", "UPI", "paymentToken", "tok_ok"),
                Map.of("Idempotency-Key", key));
    }

    protected long subscribeAndPay(String token, long memberId, long planId) {
        Api.Resp sub = subscribe(token, memberId, planId);
        Api.Resp pay = payInvoice(token, sub.body().get("invoiceId").asLong(), unique("pay-key-"));
        assertThat(pay.status()).as(pay.toString()).isEqualTo(201);
        return sub.id();
    }

    protected Api.Resp runLifecycle() {
        Api.Resp r = api.post("/api/v1/subscriptions/lifecycle/run", admin(), null);
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r;
    }
}
