package com.gymops.classes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.gymops.support.Api;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Booking rules end to end: capacity, waitlist promotion, cancellation cutoff, credits, no-show block, races. */
class ClassBookingIT extends ClassesITBase {

    @BeforeEach
    void setUp() {
        setUpBranch();
    }

    @Test
    void capacityWaitlistAndFifoPromotionOnCancel() {
        long planId = plan(null, false);
        MemberLogin a = member(planId);
        MemberLogin b = member(planId);
        MemberLogin c = member(planId);
        MemberLogin d = member(planId);
        long sessionId = session(Duration.ofHours(5), 1, 2);

        Api.Resp booked = bookSelf(sessionId, a);
        assertThat(booked.status()).as(booked.toString()).isEqualTo(201);
        assertThat(booked.body().get("status").asText()).isEqualTo("BOOKED");
        Api.Resp waitB = bookSelf(sessionId, b);
        assertThat(waitB.body().get("status").asText()).isEqualTo("WAITLISTED");
        assertThat(waitB.body().get("waitlistPosition").asInt()).isEqualTo(1);
        Api.Resp waitC = bookFor(sessionId, c.id());
        assertThat(waitC.status()).as("front desk books for a member").isEqualTo(201);
        assertThat(waitC.body().get("waitlistPosition").asInt()).isEqualTo(2);
        Api.Resp full = bookSelf(sessionId, d);
        assertThat(full.status()).as(full.toString()).isEqualTo(409);
        assertThat(full.body().get("message").asText()).startsWith("Class is full");
        assertThat(bookSelf(sessionId, a).status()).as("one live booking per member").isEqualTo(409);

        // counts on the timetable and the member's own status
        JsonNode list = api.get("/api/v1/classes/sessions?branchId=" + branchId, b.token()).body().get("content");
        JsonNode row = find(list, sessionId);
        assertThat(row.get("booked").asLong()).isEqualTo(1);
        assertThat(row.get("waitlisted").asLong()).isEqualTo(2);
        assertThat(row.get("spotsLeft").asLong()).isZero();
        assertThat(row.get("myBookingStatus").asText()).isEqualTo("WAITLISTED");

        // members cannot cancel other members' bookings
        assertThat(api.post("/api/v1/classes/bookings/" + booked.id() + "/cancel", b.token(), null).status()).isEqualTo(403);

        // early cancellation: free, and the first waitlisted member (B, FIFO) takes the seat
        Api.Resp cancelled = api.post("/api/v1/classes/bookings/" + booked.id() + "/cancel", a.token(), null);
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(bookingStatus(waitB.id())).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("select promoted_at is not null from class_bookings where id = ?", Boolean.class,
                waitB.id())).isTrue();
        assertThat(bookingStatus(waitC.id())).isEqualTo("WAITLISTED");
        assertThat(notifications(b.id(), "CLASS_WAITLIST_PROMOTED")).isEqualTo(1);
        assertThat(notifications(a.id(), "CLASS_BOOKED")).isEqualTo(1);
        assertThat(notifications(c.id(), "CLASS_WAITLISTED")).isEqualTo(1);

        Api.Resp detail = api.get("/api/v1/classes/sessions/" + sessionId, desk);
        assertThat(detail.body().get("roster")).hasSize(3);
        assertThat(detail.body().get("canMarkAttendance").asBoolean()).isTrue();
        Api.Resp memberView = api.get("/api/v1/classes/sessions/" + sessionId, b.token());
        assertThat(memberView.body().get("roster").isNull()).as("members never see the roster").isTrue();
        assertThat(memberView.body().at("/session/myBookingStatus").asText()).isEqualTo("BOOKED");

        // the waitlisted member leaves; my bookings list shows upcoming
        assertThat(api.post("/api/v1/classes/bookings/" + waitC.id() + "/cancel", c.token(), null).body().get("status")
                .asText()).isEqualTo("CANCELLED");
        Api.Resp mine = api.get("/api/v1/classes/bookings/me?when=UPCOMING", b.token());
        assertThat(mine.body().get("content").get(0).get("status").asText()).isEqualTo("BOOKED");
        assertThat(api.get("/api/v1/classes/bookings/me?when=PAST", b.token()).body().get("totalElements").asLong()).isZero();
        JsonNode unlimited = api.get("/api/v1/classes/credits/me", b.token()).body();
        assertThat(unlimited.get("unlimited").asBoolean()).as(unlimited.toString()).isTrue();
        assertThat(unlimited.get("includesClasses").asBoolean()).isTrue();
        assertThat(unlimited.get("remaining").isNull()).isTrue();
    }

    @Test
    void cancellingAfterTheCutoffIsLateAndUsesACredit() {
        long planId = plan(4, false);
        MemberLogin m = member(planId);
        long soon = session(Duration.ofMinutes(90), 5, 0);   // default cutoff is 120 minutes before start
        long booking = bookSelf(soon, m).id();
        Api.Resp late = api.post("/api/v1/classes/bookings/" + booking + "/cancel", m.token(), null);
        assertThat(late.status()).as(late.toString()).isEqualTo(200);
        assertThat(late.body().get("status").asText()).isEqualTo("LATE_CANCELLED");

        Api.Resp credits = api.get("/api/v1/classes/credits/me", m.token());
        assertThat(credits.status()).as(credits.toString()).isEqualTo(200);
        int expectedUsed = YearMonth.from(startIn(Duration.ofMinutes(90)).atZone(ZoneId.of("Asia/Kolkata")))
                .equals(YearMonth.now(clock)) ? 1 : 0;
        assertThat(credits.body().get("used").asLong()).isEqualTo(expectedUsed);
        assertThat(credits.body().get("creditsPerMonth").asInt()).isEqualTo(4);
        assertThat(api.get("/api/v1/classes/members/" + m.id() + "/credits", desk).status()).isEqualTo(200);

        // a late-cancelled member may book again (the seat was released)
        assertThat(bookSelf(soon, m).body().get("status").asText()).isEqualTo("BOOKED");
    }

    @Test
    void concurrentBookingOfTheLastSpotHasOneWinner() throws Exception {
        long planId = plan(null, false);
        int contenders = 8;
        List<Long> members = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            members.add(memberWithoutLogin(planId));
        }
        long sessionId = session(Duration.ofHours(6), 1, 2);
        List<Callable<Api.Resp>> calls = new ArrayList<>();
        for (Long memberId : members) {
            calls.add(() -> bookFor(sessionId, memberId));
        }
        List<Api.Resp> results = concurrently(calls);

        assertThat(results.stream().filter(r -> r.status() == 201 && r.body().get("status").asText().equals("BOOKED")))
                .as(results.toString()).hasSize(1);
        assertThat(results.stream().filter(r -> r.status() == 201 && r.body().get("status").asText().equals("WAITLISTED")))
                .hasSize(2);
        assertThat(results.stream().filter(r -> r.status() == 409)).hasSize(contenders - 3);
        assertThat(jdbc.queryForObject("select count(*) from class_bookings where session_id = ? and status = 'BOOKED'",
                Long.class, sessionId)).isEqualTo(1);
        List<Integer> positions = results.stream().filter(r -> r.status() == 201)
                .filter(r -> r.body().get("status").asText().equals("WAITLISTED"))
                .map(r -> r.body().get("waitlistPosition").asInt()).sorted().toList();
        assertThat(positions).containsExactly(1, 2);

        // two concurrent cancellations of the same seat: one succeeds, the seat is promoted exactly once
        long winner = results.stream().filter(r -> r.status() == 201 && r.body().get("status").asText().equals("BOOKED"))
                .findFirst().orElseThrow().id();
        List<Api.Resp> cancels = concurrently(List.of(
                () -> api.post("/api/v1/classes/bookings/" + winner + "/cancel", desk, null),
                () -> api.post("/api/v1/classes/bookings/" + winner + "/cancel", desk, null)));
        assertThat(cancels.stream().filter(r -> r.status() == 200)).hasSize(1);
        assertThat(cancels.stream().filter(r -> r.status() == 409)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from class_bookings where session_id = ? and status = 'BOOKED'",
                Long.class, sessionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from class_bookings where session_id = ? and status = 'WAITLISTED'",
                Long.class, sessionId)).isEqualTo(1);
    }

    @Test
    void monthlyCreditsAndPlanRulesLimitBooking() {
        MemberLogin noClasses = member(plan(0, false));
        MemberLogin twoCredits = member(plan(2, false));
        // three classes on the same local day, 30 minutes apart (one instructor, no overlap)
        Duration base = Duration.ofMinutes(150);
        Instant first = startIn(base);
        if (!YearMonth.from(first.atZone(ZoneId.of("Asia/Kolkata")))
                .equals(YearMonth.from(first.plus(Duration.ofHours(2)).atZone(ZoneId.of("Asia/Kolkata"))))) {
            base = base.plus(Duration.ofHours(3));   // never straddle a month boundary
        }
        long s1 = session(base, 10, 0);
        long s2 = session(base.plusMinutes(40), 10, 0);
        long s3 = session(base.plusMinutes(80), 10, 0);

        Api.Resp denied = bookSelf(s1, noClasses);
        assertThat(denied.status()).isEqualTo(422);
        assertThat(denied.body().get("message").asText()).isEqualTo("Your membership plan does not include group classes");

        assertThat(bookSelf(s1, twoCredits).status()).isEqualTo(201);
        long second = bookSelf(s2, twoCredits).id();
        Api.Resp third = bookSelf(s3, twoCredits);
        assertThat(third.status()).as(third.toString()).isEqualTo(422);
        assertThat(third.body().get("message").asText()).contains("All 2 class credits");
        assertThat(api.get("/api/v1/classes/credits/me", twoCredits.token()).body().get("remaining").asInt()).isZero();

        // an early cancellation returns the credit
        assertThat(api.post("/api/v1/classes/bookings/" + second + "/cancel", twoCredits.token(), null).status()).isEqualTo(200);
        assertThat(bookSelf(s3, twoCredits).status()).isEqualTo(201);

        // home-branch plans cannot book at another branch; members without membership cannot book at all
        String admin = admin();
        long otherBranch = createBranch(admin);
        String otherManager = login(createStaff(admin, "MANAGER", otherBranch), STAFF_PASSWORD);
        long otherTrainer = createTrainer(admin, createStaff(admin, "TRAINER", otherBranch), otherBranch);
        Api.Resp elsewhere = api.post("/api/v1/classes/sessions", otherManager, Map.of("classTypeId", classTypeId,
                "branchId", otherBranch, "instructorId", otherTrainer, "startsAt", startIn(Duration.ofHours(3)).toString()));
        assertThat(elsewhere.status()).as(elsewhere.toString()).isEqualTo(201);
        assertThat(api.post("/api/v1/classes/sessions", manager, Map.of("classTypeId", classTypeId, "branchId", otherBranch,
                "instructorId", otherTrainer, "startsAt", startIn(Duration.ofHours(5)).toString())).status())
                .as("manager of another branch").isEqualTo(403);
        long staffBooking = api.post("/api/v1/classes/sessions/" + elsewhere.id() + "/bookings",
                login(createStaff(admin, "RECEPTIONIST", otherBranch), STAFF_PASSWORD),
                Map.of("memberId", twoCredits.id())).status();
        assertThat(staffBooking).as("home-branch plan at another branch").isEqualTo(422);
        long unsubscribed = createMember(desk, branchId);
        Api.Resp noMembership = bookFor(s1, unsubscribed);
        assertThat(noMembership.status()).isEqualTo(422);
        assertThat(noMembership.body().get("code").asText()).isEqualTo("MEMBERSHIP_NOT_ACTIVE");
    }

    @Test
    void threeNoShowsBlockBookingForSevenDays() {
        MemberLogin m = member(plan(null, false));
        long other = memberWithoutLogin(plan(null, false));
        List<Long> bookings = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long s = session(Duration.ofMinutes(10 + i * 40L), 10, 0);
            bookings.add(bookSelf(s, m).id());
        }
        long future = session(Duration.ofDays(2), 10, 0);
        assertThat(api.post("/api/v1/classes/sessions/" + future + "/bookings", desk, Map.of("memberId", m.id())).status())
                .as("not blocked yet").isEqualTo(201);
        long laterClass = session(Duration.ofDays(3), 10, 0);

        clock.advance(Duration.ofHours(3));   // all three classes are over
        relogin();
        String trainer = login(trainerEmail, STAFF_PASSWORD);
        for (Long booking : bookings) {
            Api.Resp marked = api.patch("/api/v1/classes/bookings/" + booking + "/attendance", trainer,
                    Map.of("status", "NO_SHOW"));
            assertThat(marked.status()).as(marked.toString()).isEqualTo(200);
        }
        Api.Resp blocked = bookFor(laterClass, m.id());
        assertThat(blocked.status()).as(blocked.toString()).isEqualTo(422);
        assertThat(blocked.body().get("message").asText()).startsWith("Class booking is blocked until")
                .contains("3 no-shows within 30 days");
        assertThat(bookFor(laterClass, other).status()).as("other members unaffected").isEqualTo(201);
        Api.Resp credits = api.get("/api/v1/classes/members/" + m.id() + "/credits", desk);
        assertThat(credits.body().get("recentNoShows").asInt()).isEqualTo(3);
        assertThat(credits.body().get("bookingBlockedUntil").isNull()).isFalse();

        clock.advance(Duration.ofDays(7).plusHours(1));
        relogin();
        long nextWeek = session(Duration.ofHours(4), 10, 0);
        assertThat(bookFor(nextWeek, m.id()).status()).as("block lifted after 7 days").isEqualTo(201);
    }

    private static JsonNode find(JsonNode list, long id) {
        for (JsonNode n : list) {
            if (n.get("id").asLong() == id) {
                return n;
            }
        }
        throw new AssertionError("session " + id + " not listed in " + list);
    }
}
