package com.gymops.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymops.security.RateLimiter;
import com.gymops.support.Api;
import com.gymops.support.IntegrationTestBase;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SecurityIT extends IntegrationTestBase {

    @Autowired RateLimiter rateLimiter;

    @Test
    void refreshTokenRotationDetectsReuseAndRevokesTheFamily() {
        Api.Resp login = api.post("/api/v1/auth/login", null, Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        String first = login.body().get("refreshToken").asText();
        Api.Resp rotated = api.post("/api/v1/auth/refresh", null, Map.of("refreshToken", first));
        assertThat(rotated.status()).isEqualTo(200);
        String second = rotated.body().get("refreshToken").asText();

        assertThat(api.post("/api/v1/auth/refresh", null, Map.of("refreshToken", first)).status()).isEqualTo(401);
        assertThat(api.post("/api/v1/auth/refresh", null, Map.of("refreshToken", second)).status()).isEqualTo(401);
        // passwords are never stored in clear text
        String hash = jdbc.queryForObject("select password_hash from users where email = ?", String.class, ADMIN_EMAIL);
        assertThat(hash).startsWith("{bcrypt}$2").doesNotContain(ADMIN_PASSWORD);
    }

    @Test
    void accountLocksAfterRepeatedFailedLogins() {
        String admin = admin();
        long branchId = createBranch(admin);
        String email = createStaff(admin, "MANAGER", branchId);
        for (int i = 0; i < 5; i++) {
            assertThat(api.post("/api/v1/auth/login", null, Map.of("email", email, "password", "WrongPassword1")).status())
                    .isEqualTo(401);
        }
        Api.Resp locked = api.post("/api/v1/auth/login", null, Map.of("email", email, "password", STAFF_PASSWORD));
        assertThat(locked.status()).isEqualTo(423);
        assertThat(locked.body().get("code").asText()).isEqualTo("ACCOUNT_LOCKED");
    }

    @Test
    void branchScopingAndMemberIsolation() {
        String admin = admin();
        long branchA = createBranch(admin);
        long branchB = createBranch(admin);
        String deskA = login(createStaff(admin, "RECEPTIONIST", branchA), STAFF_PASSWORD);
        String deskB = login(createStaff(admin, "RECEPTIONIST", branchB), STAFF_PASSWORD);
        long memberOfB = createMember(deskB, branchB);

        assertThat(api.get("/api/v1/members/" + memberOfB, deskA).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/members", deskA, Map.of("homeBranchId", branchB, "firstName", "X", "lastName", "Y",
                "email", unique("x") + "@example.com", "phone", "+91 9000000000")).status()).isEqualTo(403);
        Api.Resp listA = api.get("/api/v1/members?branchId=" + branchB, deskA);
        assertThat(listA.status()).isEqualTo(403);

        Api.Resp self = api.post("/api/v1/auth/register", null, Map.of("email", unique("self") + "@example.com",
                "password", "MemberPassw0rd", "firstName", "Self", "lastName", "Member", "phone", "+91 9111111111",
                "homeBranchId", branchA));
        assertThat(self.status()).isEqualTo(201);
        String memberToken = self.body().get("accessToken").asText();
        assertThat(api.get("/api/v1/members/" + memberOfB, memberToken).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/dashboard/admin", memberToken).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/members/me", memberToken).status()).isEqualTo(200);
    }

    @Test
    void memberSeesComplaintsFiledAboutThemWithStaffNames() {
        String admin = admin();
        long branchId = createBranch(admin);
        String managerEmail = createStaff(admin, "MANAGER", branchId);
        String manager = login(managerEmail, STAFF_PASSWORD);
        Api.Resp self = api.post("/api/v1/auth/register", null, Map.of("email", unique("cm") + "@example.com",
                "password", "MemberPassw0rd", "firstName", "Com", "lastName", "Plainer", "phone", "+91 9111111111",
                "homeBranchId", branchId));
        String memberToken = self.body().get("accessToken").asText();
        long memberId = self.body().at("/user/memberId").asLong();
        Api.Resp filed = api.post("/api/v1/complaints", manager, Map.of("memberId", memberId, "category", "BILLING",
                "subject", "Double charge", "description", "Reported by phone"));
        assertThat(filed.status()).isEqualTo(201);
        assertThat(filed.body().get("raisedByName").asText()).isEqualTo("Staff MANAGER");

        assertThat(api.get("/api/v1/complaints/" + filed.id(), memberToken).status())
                .as("member can open a ticket filed about them").isEqualTo(200);
        assertThat(api.get("/api/v1/complaints", memberToken).body().get("totalElements").asLong()).isEqualTo(1);
        api.post("/api/v1/complaints/" + filed.id() + "/comments", manager, Map.of("body", "Refund issued", "internal", false));
        assertThat(api.get("/api/v1/complaints/" + filed.id() + "/comments", memberToken).body().get(0)
                .get("authorName").asText()).isEqualTo("Staff MANAGER");
    }

    @Test
    void gymAdminCannotEscalateToSuperAdmin() {
        String admin = admin();
        String gymAdminEmail = unique("gymadmin") + "@example.com";
        assertThat(api.post("/api/v1/users", admin, Map.of("email", gymAdminEmail, "initialPassword", STAFF_PASSWORD,
                "fullName", "GA", "roles", java.util.List.of("GYM_ADMIN"))).status()).isEqualTo(201);
        String gymAdmin = login(gymAdminEmail, STAFF_PASSWORD);
        Api.Resp escalate = api.post("/api/v1/users", gymAdmin, Map.of("email", unique("evil") + "@example.com",
                "initialPassword", STAFF_PASSWORD, "fullName", "Evil", "roles", java.util.List.of("SUPER_ADMIN")));
        assertThat(escalate.status()).isEqualTo(403);

        // a GYM_ADMIN can neither demote the SUPER_ADMIN nor change their own roles
        Long superAdminId = jdbc.queryForObject("select id from users where email = ?", Long.class, ADMIN_EMAIL);
        Long gymAdminId = jdbc.queryForObject("select id from users where email = ?", Long.class, gymAdminEmail);
        assertThat(api.send("PUT", "/api/v1/users/" + superAdminId + "/roles", gymAdmin,
                Map.of("roles", java.util.List.of("MANAGER")), Map.of()).status()).isEqualTo(403);
        assertThat(api.send("PUT", "/api/v1/users/" + gymAdminId + "/roles", gymAdmin,
                Map.of("roles", java.util.List.of("MANAGER")), Map.of()).status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select count(*) from user_roles ur join roles r on r.id = ur.role_id "
                + "where ur.user_id = ? and r.name = 'SUPER_ADMIN'", Long.class, superAdminId)).isEqualTo(1);
    }

    @Test
    void redisBackedRateLimiterEnforcesWindowAcrossCalls() {
        String key = unique("rl-test");
        for (int i = 0; i < 3; i++) {
            assertThat(rateLimiter.tryAcquire(key, 3, Duration.ofMinutes(1)).allowed()).isTrue();
        }
        RateLimiter.Decision blocked = rateLimiter.tryAcquire(key, 3, Duration.ofMinutes(1));
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isPositive();
    }
}
