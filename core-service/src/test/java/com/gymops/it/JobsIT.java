package com.gymops.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymops.attendance.AttendanceAutoCheckoutJob;
import com.gymops.complaint.ComplaintEscalationJob;
import com.gymops.notification.NotificationDispatcher;
import com.gymops.support.Api;
import com.gymops.support.IntegrationTestBase;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Background jobs, driven directly with the mutable test clock (scheduling is disabled in tests). */
class JobsIT extends IntegrationTestBase {

    @Autowired AttendanceAutoCheckoutJob autoCheckoutJob;
    @Autowired ComplaintEscalationJob escalationJob;
    @Autowired NotificationDispatcher dispatcher;

    @Test
    void forgottenCheckOutIsClosedAutomatically() {
        String admin = admin();
        long branchId = createBranch(admin);
        String desk = login(createStaff(admin, "RECEPTIONIST", branchId), STAFF_PASSWORD);
        long memberId = createMember(desk, branchId);
        subscribeAndPay(desk, memberId, createPlan(admin, "MONTHLY", "2000.00", "PRORATED", 0, Map.of()));
        Map<String, Object> checkIn = Map.of("memberId", memberId, "branchId", branchId);
        long visitId = api.post("/api/v1/attendance/check-in", desk, checkIn).id();

        autoCheckoutJob.run();   // too early: still open
        assertThat(jdbc.queryForObject("select check_out_time is null from attendance where id = ?", Boolean.class, visitId))
                .isTrue();

        clock.advance(Duration.ofHours(13));
        autoCheckoutJob.run();
        Map<String, Object> visit = jdbc.queryForMap(
                "select auto_checked_out, extract(epoch from check_out_time - check_in_time) / 60 as minutes "
                        + "from attendance where id = ?", visitId);
        assertThat(visit.get("auto_checked_out")).isEqualTo(true);
        assertThat(((Number) visit.get("minutes")).intValue()).isEqualTo(90);
        assertThat(jdbc.queryForObject("select count(*) from notifications where member_id = ? and type = 'VISIT_AUTO_CLOSED'",
                Long.class, memberId)).isPositive();

        String deskLater = login(createStaff(admin(), "RECEPTIONIST", branchId), STAFF_PASSWORD);
        assertThat(api.post("/api/v1/attendance/check-in", deskLater, checkIn).status())
                .as("member can check in again").isEqualTo(201);
    }

    @Test
    void breachedComplaintSlaEscalatesAndNotifiesManagers() {
        String admin = admin();
        long branchId = createBranch(admin);
        String managerEmail = createStaff(admin, "MANAGER", branchId);
        String manager = login(managerEmail, STAFF_PASSWORD);
        Long managerId = jdbc.queryForObject("select id from users where email = ?", Long.class, managerEmail);
        long id = api.post("/api/v1/complaints", manager, Map.of("branchId", branchId, "category", "EQUIPMENT",
                "priority", "CRITICAL", "subject", "Cable snapped", "description", "Lat pulldown cable snapped")).id();

        clock.advance(Duration.ofHours(5));   // CRITICAL SLA is 4h
        escalationJob.run();
        String later = login(managerEmail, STAFF_PASSWORD);
        Api.Resp c = api.get("/api/v1/complaints/" + id, later);
        assertThat(c.body().get("status").asText()).isEqualTo("ESCALATED");
        assertThat(c.body().get("escalationLevel").asInt()).isEqualTo(1);

        escalationJob.run();   // new SLA (2h) not yet breached: no double escalation
        assertThat(api.get("/api/v1/complaints/" + id, later).body().get("escalationLevel").asInt()).isEqualTo(1);

        clock.advance(Duration.ofHours(3));
        escalationJob.run();
        assertThat(api.get("/api/v1/complaints/" + id, login(managerEmail, STAFF_PASSWORD)).body()
                .get("escalationLevel").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from notifications where type = 'COMPLAINT_ESCALATED' "
                + "and recipient_user_id = ?", Long.class, managerId)).isEqualTo(2);
    }

    @Test
    void dispatcherDeliversOutboxAndRetriesUndeliverableWithBackoff() {
        Long okId = jdbc.queryForObject("""
                insert into notifications (type, channel, destination, title, body, status)
                values ('PAYMENT_CONFIRMATION', 'EMAIL', 'someone@example.com', 'Hi', 'Body', 'PENDING') returning id
                """, Long.class);
        // no PUSH sender is configured -> delivery fails and is retried with backoff until FAILED
        Long smsId = jdbc.queryForObject("""
                insert into notifications (type, channel, destination, title, body, status)
                values ('PAYMENT_CONFIRMATION', 'PUSH', 'device-token-1', 'Hi', 'Body', 'PENDING') returning id
                """, Long.class);

        dispatcher.dispatch();
        assertThat(jdbc.queryForObject("select status from notifications where id = ?", String.class, okId)).isEqualTo("SENT");
        Map<String, Object> sms = jdbc.queryForMap("select status, attempts, next_attempt_at > now() as backed_off "
                + "from notifications where id = ?", smsId);
        assertThat(sms.get("status")).isEqualTo("PENDING");
        assertThat(sms.get("attempts")).isEqualTo(1);
        assertThat(sms.get("backed_off")).isEqualTo(true);

        dispatcher.dispatch();   // not due yet: untouched
        assertThat(jdbc.queryForObject("select attempts from notifications where id = ?", Integer.class, smsId)).isEqualTo(1);

        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofMinutes(20));
            dispatcher.dispatch();
        }
        Map<String, Object> failed = jdbc.queryForMap("select status, attempts, last_error from notifications where id = ?", smsId);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("attempts")).isEqualTo(5);
        assertThat((String) failed.get("last_error")).contains("No sender configured for channel PUSH");
    }
}
