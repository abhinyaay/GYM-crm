package com.gymops.classes;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymops.support.Api;
import com.gymops.support.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Shared fixtures for group-class integration tests: a branch with a manager, a front desk and an instructor. */
abstract class ClassesITBase extends IntegrationTestBase {

    /** A self-registered member with a paid membership: id plus a login token. */
    protected record MemberLogin(long id, String token) {
    }

    protected long branchId;
    protected String managerEmail;
    protected String deskEmail;
    protected String trainerEmail;
    protected String manager;
    protected String desk;
    protected long trainerId;
    protected long classTypeId;

    protected void setUpBranch() {
        String admin = admin();
        branchId = createBranch(admin);
        managerEmail = createStaff(admin, "MANAGER", branchId);
        deskEmail = createStaff(admin, "RECEPTIONIST", branchId);
        manager = login(managerEmail, STAFF_PASSWORD);
        desk = login(deskEmail, STAFF_PASSWORD);
        trainerEmail = createStaff(admin, "TRAINER", branchId);
        trainerId = createTrainer(admin, trainerEmail, branchId);
        classTypeId = createClassType(manager, 20);
    }

    /** Fresh tokens (access tokens live 15 minutes; tests that move the clock must log in again). */
    protected void relogin() {
        manager = login(managerEmail, STAFF_PASSWORD);
        desk = login(deskEmail, STAFF_PASSWORD);
    }

    protected long createTrainer(String admin, String email, long branch) {
        long userId = jdbc.queryForObject("select id from users where email = ?", Long.class, email);
        Api.Resp trainer = api.post("/api/v1/trainers", admin, Map.of("userId", userId, "homeBranchId", branch,
                "employeeCode", unique("E").replaceAll("[^A-Za-z0-9]", ""), "yearsOfExperience", 4));
        assertThat(trainer.status()).as(trainer.toString()).isEqualTo(201);
        return trainer.id();
    }

    protected long createClassType(String token, int capacity) {
        Api.Resp type = api.post("/api/v1/classes/types", token, Map.of("name", unique("Yoga "), "category", "YOGA",
                "intensity", "LOW", "defaultDurationMin", 45, "defaultCapacity", capacity));
        assertThat(type.status()).as(type.toString()).isEqualTo(201);
        return type.id();
    }

    /** A class starting {@code startsIn} from now (truncated to the minute), 30 minutes long. */
    protected Api.Resp createSession(Duration startsIn, int capacity, int waitlist, long instructor) {
        Map<String, Object> body = new HashMap<>(Map.of("classTypeId", classTypeId, "branchId", branchId,
                "instructorId", instructor, "startsAt", startIn(startsIn).toString(), "durationMinutes", 30,
                "capacity", capacity, "waitlistCapacity", waitlist));
        return api.post("/api/v1/classes/sessions", manager, body);
    }

    protected long session(Duration startsIn, int capacity, int waitlist) {
        Api.Resp r = createSession(startsIn, capacity, waitlist, trainerId);
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.id();
    }

    protected Instant startIn(Duration d) {
        return Instant.now(clock).plus(d).truncatedTo(ChronoUnit.MINUTES);
    }

    protected long plan(Integer classCredits, boolean allBranches) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("allBranchAccess", allBranches);
        if (classCredits != null) {
            extra.put("classCreditsPerMonth", classCredits);
        }
        return createPlan(admin(), "MONTHLY", "2000.00", "PRORATED", 3, extra);
    }

    protected MemberLogin member(long planId) {
        Api.Resp self = api.post("/api/v1/auth/register", null, Map.of("email", unique("cls") + "@example.com",
                "password", "MemberPassw0rd", "firstName", "Class", "lastName", "Goer", "phone", "+91 9111111111",
                "homeBranchId", branchId));
        assertThat(self.status()).as(self.toString()).isEqualTo(201);
        long memberId = self.body().at("/user/memberId").asLong();
        subscribeAndPay(desk, memberId, planId);
        return new MemberLogin(memberId, self.body().get("accessToken").asText());
    }

    protected long memberWithoutLogin(long planId) {
        long memberId = createMember(desk, branchId);
        subscribeAndPay(desk, memberId, planId);
        return memberId;
    }

    protected Api.Resp bookSelf(long sessionId, MemberLogin m) {
        return api.post("/api/v1/classes/sessions/" + sessionId + "/bookings", m.token(), Map.of());
    }

    protected Api.Resp bookFor(long sessionId, long memberId) {
        return api.post("/api/v1/classes/sessions/" + sessionId + "/bookings", desk, Map.of("memberId", memberId));
    }

    protected String bookingStatus(long bookingId) {
        return jdbc.queryForObject("select status from class_bookings where id = ?", String.class, bookingId);
    }

    protected long notifications(long memberId, String type) {
        return jdbc.queryForObject("select count(*) from notifications where member_id = ? and type = ? and channel = 'EMAIL'",
                Long.class, memberId, type);
    }

    protected static List<Api.Resp> concurrently(List<Callable<Api.Resp>> calls) throws Exception {
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
}
