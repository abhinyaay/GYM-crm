package com.gymops.growth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.gymops.growth.campaign.ChurnRiskSource;
import com.gymops.support.Api;
import com.gymops.support.IntegrationTestBase;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Shared fixtures for the growth ITs: a branch with a manager and a front desk, a paid plan and a
 * mocked churn-risk source (the analytics base URL is unreachable in tests). Subclasses share one context.
 */
@TestPropertySource(properties = {"app.growth.referral.monthly-cap=2", "app.growth.referral.hold-days=14"})
public abstract class GrowthITBase extends IntegrationTestBase {

    @MockitoBean protected ChurnRiskSource churnRisk;

    /** A member with a login (id + token). */
    protected record MemberLogin(long id, String token, String email, String phone) {
    }

    protected long branchId;
    protected String managerEmail;
    protected String deskEmail;
    protected String manager;
    protected String desk;
    protected long planId;

    protected void setUpBranch() {
        String admin = admin();
        branchId = createBranch(admin);
        managerEmail = createStaff(admin, "MANAGER", branchId);
        deskEmail = createStaff(admin, "RECEPTIONIST", branchId);
        planId = createPlan(admin, "MONTHLY", "1000.00", "PRORATED", 0, Map.of());
        relogin();
    }

    /** Access tokens live 15 minutes: log in again after moving the clock. */
    protected void relogin() {
        manager = login(managerEmail, STAFF_PASSWORD);
        desk = login(deskEmail, STAFF_PASSWORD);
    }

    protected void advance(Duration d) {
        clock.advance(d);
        relogin();
    }

    protected MemberLogin registerMember(String first) {
        String email = unique(first.toLowerCase()) + "@example.com";
        String phone = "+91 9" + String.format("%09d", Math.abs(email.hashCode()) % 1_000_000_000);
        Api.Resp self = api.post("/api/v1/auth/register", null, Map.of("email", email, "password", "MemberPassw0rd",
                "firstName", first, "lastName", "Tester", "phone", phone, "homeBranchId", branchId));
        assertThat(self.status()).as(self.toString()).isEqualTo(201);
        return new MemberLogin(self.body().at("/user/memberId").asLong(), self.body().get("accessToken").asText(), email,
                phone);
    }

    protected String memberToken(MemberLogin m) {
        return login(m.email(), "MemberPassw0rd");
    }

    /** Desk-registered member without a login. */
    protected long deskMember(String first, String email, String phone) {
        Api.Resp r = api.post("/api/v1/members", desk, Map.of("homeBranchId", branchId, "firstName", first,
                "lastName", "Prospect", "email", email, "phone", phone));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.id();
    }

    protected Api.Resp subscribeWithCode(String token, long memberId, String code) {
        Map<String, Object> body = new HashMap<>(Map.of("memberId", memberId, "planId", planId));
        if (code != null) {
            body.put("discountCode", code);
        }
        return api.post("/api/v1/subscriptions", token, body);
    }

    protected long pay(String token, long invoiceId) {
        Api.Resp pay = payInvoice(token, invoiceId, unique("pay-"));
        assertThat(pay.status()).as(pay.toString()).isEqualTo(201);
        return pay.id();
    }

    protected Api.Resp runGrowthJobs() {
        Api.Resp r = api.post("/api/v1/growth/jobs/run", admin(), null);
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r;
    }

    protected JsonNode referralOf(long refereeMemberId) {
        for (JsonNode r : api.get("/api/v1/referrals?branchId=" + branchId + "&size=100", manager).body().get("content")) {
            if (r.get("refereeMemberId").asLong() == refereeMemberId) {
                return r;
            }
        }
        return null;
    }

    protected long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
