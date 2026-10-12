package com.gymops.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymops.support.Api;
import com.gymops.support.IntegrationTestBase;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrainerWorkoutIT extends IntegrationTestBase {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private long userId(String email) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email);
    }

    private long createTrainer(String admin, long branchId) {
        String email = createStaff(admin, "TRAINER", branchId);
        Api.Resp trainer = api.post("/api/v1/trainers", admin, Map.of("userId", userId(email), "homeBranchId", branchId,
                "employeeCode", unique("E").replaceAll("[^A-Za-z0-9]", ""), "yearsOfExperience", 5, "hourlyRate", "1000.00",
                "specializations", List.of("strength", "hiit")));
        assertThat(trainer.status()).as(trainer.toString()).isEqualTo(201);
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            slots.add(Map.of("branchId", branchId, "dayOfWeek", day, "startTime", "06:00", "endTime", "21:00"));
        }
        Api.Resp availability = api.send("PUT", "/api/v1/trainers/" + trainer.id() + "/availability", admin,
                Map.of("slots", slots), Map.of());
        assertThat(availability.status()).as(availability.toString()).isEqualTo(200);
        trainerEmails.put(trainer.id(), email);
        return trainer.id();
    }

    private final Map<Long, String> trainerEmails = new java.util.HashMap<>();

    @Test
    void trainerAssignmentSessionsWorkoutPlansProgressAndDashboard() {
        String admin = admin();
        long branchId = createBranch(admin);
        String desk = login(createStaff(admin, "RECEPTIONIST", branchId), STAFF_PASSWORD);
        long trainerId = createTrainer(admin, branchId);
        long otherTrainerId = createTrainer(admin, branchId);
        String coach = login(trainerEmails.get(trainerId), STAFF_PASSWORD);
        String otherCoach = login(trainerEmails.get(otherTrainerId), STAFF_PASSWORD);

        long memberA = createMember(desk, branchId);
        long memberB = createMember(desk, branchId);
        assertThat(api.post("/api/v1/trainers/" + trainerId + "/clients", desk,
                Map.of("memberId", memberA, "goal", "Lose 5kg")).status()).isEqualTo(201);
        assertThat(api.post("/api/v1/trainers/" + trainerId + "/clients", desk, Map.of("memberId", memberB)).status())
                .isEqualTo(201);
        assertThat(api.post("/api/v1/trainers/" + trainerId + "/clients", desk, Map.of("memberId", memberA)).status())
                .as("same trainer twice").isEqualTo(409);
        assertThat(api.get("/api/v1/trainers/" + trainerId + "/clients", otherCoach).status())
                .as("another trainer cannot see these clients").isEqualTo(403);

        // workout template -> personal plan
        Api.Resp exercises = api.get("/api/v1/workouts/exercises?size=5", coach);
        long squat = exercises.body().get("content").get(0).get("id").asLong();
        long plank = exercises.body().get("content").get(1).get("id").asLong();
        Api.Resp template = api.post("/api/v1/workouts/templates", coach, Map.of("name", "Starter", "goal", "STRENGTH",
                "difficulty", "BEGINNER", "durationWeeks", 6, "exercises", List.of(
                        Map.of("exerciseId", squat, "dayNumber", 1, "sets", 3, "reps", 10),
                        Map.of("exerciseId", plank, "dayNumber", 1, "sets", 3, "durationSeconds", 45))));
        assertThat(template.status()).as(template.toString()).isEqualTo(201);
        Api.Resp assigned = api.post("/api/v1/workouts/templates/" + template.id() + "/assign", coach,
                Map.of("memberId", memberA));
        assertThat(assigned.status()).as(assigned.toString()).isEqualTo(201);
        assertThat(assigned.body().get("template").asBoolean()).isFalse();
        assertThat(assigned.body().get("exercises")).hasSize(2);
        assertThat(api.post("/api/v1/workouts/templates/" + template.id() + "/assign", otherCoach,
                Map.of("memberId", memberA)).status()).as("not the member's trainer").isEqualTo(403);
        assertThat(api.get("/api/v1/workouts/members/" + memberA + "/active-plan", coach).body().get("id").asLong())
                .isEqualTo(assigned.id());
        // personalise the member's copy twice (replaces exercise rows guarded by a unique index)
        for (int sets : List.of(4, 5)) {
            Api.Resp edited = api.send("PUT", "/api/v1/workouts/plans/" + assigned.id(), coach, Map.of("name", "Starter",
                    "goal", "STRENGTH", "difficulty", "INTERMEDIATE", "durationWeeks", 8, "exercises", List.of(
                            Map.of("exerciseId", squat, "dayNumber", 1, "sets", sets, "reps", 8),
                            Map.of("exerciseId", plank, "dayNumber", 1, "sets", 3, "durationSeconds", 60))), Map.of());
            assertThat(edited.status()).as(edited.toString()).isEqualTo(200);
            assertThat(edited.body().get("exercises").get(0).get("sets").asInt()).isEqualTo(sets);
        }
        Api.Resp templateAfter = api.get("/api/v1/workouts/plans/" + template.id(), coach);
        assertThat(templateAfter.body().get("exercises").get(0).get("sets").asInt()).as("template untouched").isEqualTo(3);

        // a trainer may edit their own bio; sending the rate back in another scale is not a "change"
        Api.Resp profile = api.get("/api/v1/trainers/" + trainerId, coach);
        Api.Resp selfUpdate = api.send("PUT", "/api/v1/trainers/" + trainerId, coach, Map.of("homeBranchId", branchId,
                "bio", "Updated", "yearsOfExperience", 6, "hourlyRate", "1000.0"), Map.of());
        assertThat(profile.status()).isEqualTo(200);
        assertThat(selfUpdate.status()).as(selfUpdate.toString()).isEqualTo(200);
        assertThat(api.send("PUT", "/api/v1/trainers/" + trainerId, coach, Map.of("homeBranchId", branchId,
                "yearsOfExperience", 6, "hourlyRate", "5000"), Map.of()).status()).isEqualTo(403);

        assertThat(api.post("/api/v1/workouts/members/" + memberA + "/progress", coach,
                Map.of("weightKg", 82.5, "bodyFatPct", 24.0)).status()).isEqualTo(201);
        assertThat(api.post("/api/v1/workouts/members/" + memberA + "/progress", coach,
                Map.of("weightKg", 900)).status()).as("implausible weight").isEqualTo(400);

        // sessions: no double booking of the trainer
        String start = LocalDate.now(clock).plusDays(1).atTime(10, 0).atZone(IST).toInstant().toString();
        String overlapping = LocalDate.now(clock).plusDays(1).atTime(10, 30).atZone(IST).toInstant().toString();
        Api.Resp session = api.post("/api/v1/trainers/" + trainerId + "/sessions", coach,
                Map.of("memberId", memberA, "startsAt", start, "durationMinutes", 60));
        assertThat(session.status()).as(session.toString()).isEqualTo(201);
        Api.Resp clash = api.post("/api/v1/trainers/" + trainerId + "/sessions", coach,
                Map.of("memberId", memberB, "startsAt", overlapping, "durationMinutes", 60));
        assertThat(clash.status()).isEqualTo(409);
        assertThat(clash.body().get("message").asText()).isEqualTo("Trainer already has a session in this time slot");
        String lateNight = LocalDate.now(clock).plusDays(1).atTime(22, 30).atZone(IST).toInstant().toString();
        assertThat(api.post("/api/v1/trainers/" + trainerId + "/sessions", coach,
                Map.of("memberId", memberB, "startsAt", lateNight, "durationMinutes", 60)).status())
                .as("outside availability").isEqualTo(422);
        assertThat(api.patch("/api/v1/trainers/" + trainerId + "/sessions/" + session.id(), coach,
                Map.of("status", "COMPLETED")).status()).as("cannot complete a future session").isEqualTo(422);

        Api.Resp dashboard = api.get("/api/v1/dashboard/trainer", coach);
        assertThat(dashboard.status()).as(dashboard.toString()).isEqualTo(200);
        assertThat(dashboard.body().get("assignedMembers").asLong()).isEqualTo(2);
        assertThat(dashboard.body().get("activeWorkoutPlans").asLong()).isEqualTo(1);
        assertThat(api.get("/api/v1/trainers/" + trainerId + "/performance", coach).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/trainers/" + trainerId + "/schedule", coach).body()).hasSize(1);

        Long notifications = jdbc.queryForObject("select count(*) from notifications where member_id = ? and type in "
                + "('TRAINER_ASSIGNED','WORKOUT_PLAN_ASSIGNED','SESSION_SCHEDULED')", Long.class, memberA);
        assertThat(notifications).isGreaterThanOrEqualTo(3);
    }

    @Test
    void complaintEscalationAndAuditTrail() {
        String admin = admin();
        long branchId = createBranch(admin);
        String managerEmail = createStaff(admin, "MANAGER", branchId);
        String manager = login(managerEmail, STAFF_PASSWORD);
        Api.Resp complaint = api.post("/api/v1/complaints", manager, Map.of("branchId", branchId, "category", "HYGIENE",
                "priority", "HIGH", "subject", "Showers", "description", "No hot water"));
        assertThat(complaint.status()).isEqualTo(201);
        long id = complaint.id();
        assertThat(api.patch("/api/v1/complaints/" + id + "/assignee", manager,
                Map.of("assigneeUserId", userId(managerEmail))).body().get("status").asText()).isEqualTo("IN_PROGRESS");
        Api.Resp escalated = api.post("/api/v1/complaints/" + id + "/escalate", manager, Map.of("reason", "Repeated issue"));
        assertThat(escalated.body().get("status").asText()).isEqualTo("ESCALATED");
        assertThat(escalated.body().get("priority").asText()).isEqualTo("CRITICAL");
        assertThat(api.post("/api/v1/complaints/" + id + "/comments", manager,
                Map.of("body", "Plumber booked", "internal", true)).status()).isEqualTo(201);
        Api.Resp history = api.get("/api/v1/complaints/" + id + "/history", manager);
        assertThat(history.body().get("totalElements").asLong()).isGreaterThanOrEqualTo(4);
        // resolve -> reopen cycles keep working even at the maximum escalation level
        api.post("/api/v1/complaints/" + id + "/escalate", manager, Map.of("reason", "2"));
        api.post("/api/v1/complaints/" + id + "/escalate", manager, Map.of("reason", "3"));
        for (int i = 0; i < 2; i++) {
            assertThat(api.patch("/api/v1/complaints/" + id + "/status", manager,
                    Map.of("status", "RESOLVED", "resolution", "Fixed " + i)).status()).isEqualTo(200);
            Api.Resp reopened = api.patch("/api/v1/complaints/" + id + "/status", manager, Map.of("status", "REOPENED"));
            assertThat(reopened.status()).as(reopened.toString()).isEqualTo(200);
            assertThat(reopened.body().get("status").asText()).isEqualTo("REOPENED");
        }
        Long managerNotified = jdbc.queryForObject("select count(*) from notifications where type = 'COMPLAINT_ESCALATED' "
                + "and recipient_user_id = ?", Long.class, userId(managerEmail));
        assertThat(managerNotified).isEqualTo(3);
    }
}
