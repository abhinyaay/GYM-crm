package com.gymops.classes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.gymops.classes.dto.ClassDtos.JobRunSummary;
import com.gymops.support.Api;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Timetable management, cancellation cascades, the reconciliation job and reminders. */
class ClassScheduleIT extends ClassesITBase {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired ClassJobs jobs;

    @BeforeEach
    void setUp() {
        setUpBranch();
    }

    @Test
    void classTypeCatalogueIsManagedByManagers() {
        Api.Resp dup = api.post("/api/v1/classes/types", manager, Map.of("name", "  " + api.get("/api/v1/classes/types/"
                + classTypeId, desk).body().get("name").asText().toUpperCase(), "category", "HIIT", "intensity", "HIGH",
                "defaultDurationMin", 45, "defaultCapacity", 10));
        assertThat(dup.status()).as("names are unique ignoring case").isEqualTo(409);
        assertThat(api.post("/api/v1/classes/types", desk, Map.of("name", unique("X"), "category", "HIIT", "intensity", "HIGH",
                "defaultDurationMin", 45, "defaultCapacity", 10)).status()).as("front desk cannot edit catalogue").isEqualTo(403);
        assertThat(api.post("/api/v1/classes/types", manager, Map.of("name", unique("X"), "category", "HIIT",
                "intensity", "HIGH", "defaultDurationMin", 5, "defaultCapacity", 10)).status()).isEqualTo(400);

        long unused = createClassType(manager, 12);
        Api.Resp deactivated = api.send("PUT", "/api/v1/classes/types/" + unused, manager, Map.of("name", unique("Boxing"),
                "category", "BOXING", "intensity", "HIGH", "defaultDurationMin", 60, "defaultCapacity", 12, "active", false),
                Map.of());
        assertThat(deactivated.body().get("active").asBoolean()).isFalse();
        JsonNode active = api.get("/api/v1/classes/types", desk).body();
        assertThat(active.findValues("id").stream().map(JsonNode::asLong)).doesNotContain(unused);
        assertThat(api.send("DELETE", "/api/v1/classes/types/" + unused, manager, null, Map.of()).status()).isEqualTo(204);

        session(Duration.ofDays(1), 10, 0);
        assertThat(api.send("DELETE", "/api/v1/classes/types/" + classTypeId, manager, null, Map.of()).status())
                .as("types with classes are deactivated, not deleted").isEqualTo(409);
    }

    @Test
    void instructorCannotTeachOverlappingClassesAndEditsAreValidated() {
        long first = session(Duration.ofDays(1), 10, 2);
        Api.Resp clash = createSession(Duration.ofDays(1).plusMinutes(15), 10, 2, trainerId);
        assertThat(clash.status()).isEqualTo(409);
        assertThat(clash.body().get("message").asText()).startsWith("The instructor already teaches");

        long memberA = memberWithoutLogin(plan(null, false));
        long memberB = memberWithoutLogin(plan(null, false));
        bookFor(first, memberA);
        bookFor(first, memberB);
        Instant start = startIn(Duration.ofDays(1));
        Map<String, Object> edit = Map.of("instructorId", trainerId, "room", "Studio B", "startsAt", start.toString(),
                "durationMinutes", 30, "capacity", 1, "waitlistCapacity", 2, "bookingOpensHours", 168, "cancelCutoffMinutes", 60);
        Api.Resp shrink = api.send("PUT", "/api/v1/classes/sessions/" + first, manager, edit, Map.of());
        assertThat(shrink.status()).as("cannot drop below booked seats").isEqualTo(422);

        Api.Resp moved = api.send("PUT", "/api/v1/classes/sessions/" + first, manager, Map.of("instructorId", trainerId,
                "room", "Studio B", "startsAt", start.plus(Duration.ofHours(1)).toString(), "durationMinutes", 45,
                "capacity", 5, "waitlistCapacity", 2, "bookingOpensHours", 168, "cancelCutoffMinutes", 60), Map.of());
        assertThat(moved.status()).as(moved.toString()).isEqualTo(200);
        assertThat(moved.body().get("room").asText()).isEqualTo("Studio B");
        assertThat(moved.body().get("booked").asLong()).isEqualTo(2);
        assertThat(moved.body().get("durationMinutes").asLong()).isEqualTo(45);
        assertThat(jdbc.queryForObject("select count(*) from notifications where member_id in (?, ?) and type = 'CLASS_RESCHEDULED' "
                + "and title like 'Class updated%' and channel = 'EMAIL'", Long.class, memberA, memberB)).isEqualTo(2);

        String trainer = login(trainerEmail, STAFF_PASSWORD);
        Api.Resp mine = api.get("/api/v1/classes/sessions?mine=true&to=" + LocalDate.now(clock).plusDays(3), trainer);
        assertThat(mine.status()).as(mine.toString()).isEqualTo(200);
        assertThat(mine.body().get("totalElements").asLong()).isEqualTo(1);
        assertThat(api.get("/api/v1/classes/sessions/" + first, trainer).body().get("roster")).hasSize(2);
        assertThat(api.send("PUT", "/api/v1/classes/sessions/" + first, trainer, edit, Map.of()).status())
                .as("instructors cannot edit the timetable").isEqualTo(403);
    }

    @Test
    void cancellingAClassReleasesEveryBookingAndNotifiesMembers() {
        long planId = plan(2, false);
        MemberLogin a = member(planId);
        MemberLogin b = member(planId);
        long sessionId = session(Duration.ofHours(4), 1, 3);
        long seat = bookSelf(sessionId, a).id();
        long waiting = bookSelf(sessionId, b).id();

        assertThat(api.post("/api/v1/classes/sessions/" + sessionId + "/cancel", manager, Map.of()).status())
                .as("reason required").isEqualTo(400);
        Api.Resp cancelled = api.post("/api/v1/classes/sessions/" + sessionId + "/cancel", manager,
                Map.of("reason", "Studio flooded"));
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(bookingStatus(seat)).isEqualTo("CANCELLED");
        assertThat(bookingStatus(waiting)).isEqualTo("CANCELLED");
        assertThat(notifications(a.id(), "CLASS_CANCELLED")).isEqualTo(1);
        assertThat(notifications(b.id(), "CLASS_CANCELLED")).isEqualTo(1);
        assertThat(api.get("/api/v1/classes/credits/me", a.token()).body().get("used").asLong())
                .as("no credit used for a class the gym cancelled").isZero();
        assertThat(bookSelf(sessionId, a).status()).isEqualTo(409);
        assertThat(api.post("/api/v1/classes/sessions/" + sessionId + "/cancel", manager, Map.of("reason", "again")).status())
                .isEqualTo(409);
    }

    @Test
    void recurringSeriesSkipsClashesAndCanBeCancelledFromADate() {
        LocalDate nextMonday = LocalDate.now(clock).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        Instant firstSlot = nextMonday.atTime(18, 0).atZone(IST).toInstant();
        // an existing Wednesday class of the same instructor at the same time
        LocalDate wednesday = nextMonday.plusDays(2);
        Api.Resp existing = api.post("/api/v1/classes/sessions", manager, Map.of("classTypeId", classTypeId, "branchId", branchId,
                "instructorId", trainerId, "startsAt", wednesday.atTime(18, 15).atZone(IST).toInstant().toString()));
        assertThat(existing.status()).as(existing.toString()).isEqualTo(201);

        Api.Resp series = api.post("/api/v1/classes/sessions/series", manager, Map.of(
                "session", Map.of("classTypeId", classTypeId, "branchId", branchId, "instructorId", trainerId,
                        "room", "Studio A", "startsAt", firstSlot.toString(), "durationMinutes", 45),
                "daysOfWeek", List.of(1, 3, 5), "weeks", 2));
        assertThat(series.status()).as(series.toString()).isEqualTo(201);
        assertThat(series.body().get("created")).hasSize(5);
        assertThat(series.body().get("skipped")).hasSize(1);
        assertThat(series.body().at("/skipped/0/reason").asText()).startsWith("The instructor already teaches");
        String seriesId = series.body().get("seriesId").asText();
        JsonNode firstCreated = series.body().at("/created/0");
        assertThat(Instant.parse(firstCreated.get("startsAt").asText())).isEqualTo(firstSlot);
        assertThat(firstCreated.get("capacity").asInt()).as("type default").isEqualTo(20);

        Api.Resp cancelled = api.post("/api/v1/classes/series/" + seriesId + "/cancel", manager,
                Map.of("from", nextMonday.plusDays(7).toString(), "reason", "Instructor on leave"));
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(200);
        assertThat(cancelled.body().get("cancelledSessions").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from class_sessions where series_id = ?::uuid and status = 'SCHEDULED'",
                Long.class, seriesId)).isEqualTo(2);
    }

    @Test
    void reconciliationJobCompletesClassesAndMarksAttendanceFromCheckIns() {
        long planId = plan(null, false);
        long came = memberWithoutLogin(planId);
        long stayedHome = memberWithoutLogin(planId);
        long waiting = memberWithoutLogin(planId);
        long marked = memberWithoutLogin(planId);
        long sessionId = session(Duration.ofMinutes(10), 3, 2);
        long cameBooking = bookFor(sessionId, came).id();
        long absentBooking = bookFor(sessionId, stayedHome).id();
        long markedBooking = bookFor(sessionId, marked).id();
        long waitlisted = bookFor(sessionId, waiting).id();
        assertThat(bookingStatus(waitlisted)).isEqualTo("WAITLISTED");
        // reminder for a class starting within 2 hours, sent once
        JobRunSummary early = jobs.run();
        assertThat(early.remindersQueued()).isGreaterThanOrEqualTo(3);
        jobs.run();
        assertThat(notifications(came, "CLASS_REMINDER")).isEqualTo(1);

        Api.Resp checkIn = api.post("/api/v1/attendance/check-in", desk, Map.of("memberId", came, "branchId", branchId));
        assertThat(checkIn.status()).as(checkIn.toString()).isEqualTo(201);

        clock.advance(Duration.ofMinutes(25));   // class is running
        relogin();
        assertThat(api.patch("/api/v1/classes/bookings/" + markedBooking + "/attendance", desk,
                Map.of("status", "ATTENDED")).status()).isEqualTo(200);
        assertThat(jobs.run().sessionsCompleted()).as("not over yet").isZero();

        clock.advance(Duration.ofMinutes(50));   // ended 35 minutes ago
        JobRunSummary summary = jobs.run();
        assertThat(summary.sessionsCompleted()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select status from class_sessions where id = ?", String.class, sessionId))
                .isEqualTo("COMPLETED");
        assertThat(bookingStatus(cameBooking)).isEqualTo("ATTENDED");
        assertThat(bookingStatus(absentBooking)).isEqualTo("NO_SHOW");
        assertThat(bookingStatus(markedBooking)).as("manual marks are kept").isEqualTo("ATTENDED");
        assertThat(bookingStatus(waitlisted)).as("waitlist released").isEqualTo("CANCELLED");

        jobs.run();   // idempotent
        assertThat(bookingStatus(absentBooking)).isEqualTo("NO_SHOW");
        relogin();
        Api.Resp correction = api.patch("/api/v1/classes/bookings/" + absentBooking + "/attendance", desk,
                Map.of("status", "ATTENDED"));
        assertThat(correction.status()).as("front desk can correct after completion").isEqualTo(200);
    }
}
