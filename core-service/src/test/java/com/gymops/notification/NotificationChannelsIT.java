package com.gymops.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.gymops.member.MemberRepository;
import com.gymops.notification.preference.UnsubscribeTokens;
import com.gymops.support.Api;
import com.gymops.support.IntegrationTestBase;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E-mail / SMS channels end to end against PostgreSQL: preferences API and authorization, routing at enqueue time,
 * dispatch-time opt-out and quiet hours, provider receipts, unsubscribe links, test sends and resend.
 * Providers are the {@code log} ones (the defaults), so nothing leaves the test.
 */
class NotificationChannelsIT extends IntegrationTestBase {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired NotificationService notifications;
    @Autowired NotificationDispatcher dispatcher;
    @Autowired MemberRepository members;
    @Autowired TransactionTemplate tx;
    @Autowired UnsubscribeTokens unsubscribeTokens;

    // ------------------------------------------------------------------ helpers

    private void notifyMember(long memberId, NotificationType type) {
        tx.executeWithoutResult(s -> notifications.notifyMember(members.findById(memberId).orElseThrow(), type,
                "Title " + type, "Body of " + type, null));
    }

    private List<Map<String, Object>> rows(long memberId, NotificationType type) {
        return jdbc.queryForList("select id, channel, status, destination, body, next_attempt_at, last_error, provider, "
                + "provider_message_id, segments, attempts from notifications where member_id = ? and type = ? order by id",
                memberId, type.name());
    }

    private Map<String, Object> row(long memberId, NotificationType type, String channel) {
        return rows(memberId, type).stream().filter(r -> channel.equals(r.get("channel"))).findFirst().orElse(null);
    }

    private Map<String, Object> byId(long id) {
        return jdbc.queryForMap("select * from notifications where id = ?", id);
    }

    private long memberWithPhone(String token, long branchId, String phone) {
        Api.Resp r = api.post("/api/v1/members", token, Map.of("homeBranchId", branchId, "firstName", "Nia", "lastName", "Rao",
                "email", unique("notif") + "@example.com", "phone", phone));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.id();
    }

    private Api.Resp put(String path, String token, Object body) {
        return api.send("PUT", path, token, body, Map.of());
    }

    private static Map<String, Object> change(String category, String channel, boolean enabled) {
        return Map.of("category", category, "channel", channel, "enabled", enabled);
    }

    private static JsonNode channel(JsonNode prefs, String category, String channel) {
        for (JsonNode c : prefs.get("categories")) {
            if (c.get("category").asText().equals(category)) {
                for (JsonNode ch : c.get("channels")) {
                    if (ch.get("channel").asText().equals(channel)) {
                        return ch;
                    }
                }
            }
        }
        return null;
    }

    /** Moves the test clock to the next occurrence of the given business-time hour. */
    private ZonedDateTime moveClockTo(int hour) {
        ZonedDateTime now = clock.instant().atZone(IST);
        ZonedDateTime target = now.toLocalDate().atTime(LocalTime.of(hour, 0)).atZone(IST);
        if (!target.isAfter(now)) {
            target = target.plusDays(1);
        }
        clock.advance(Duration.between(now.toInstant(), target.toInstant()));
        return target;
    }

    // ------------------------------------------------------------------ preferences API

    @Test
    void preferencesHaveSensibleDefaultsAndAreProtected() {
        String admin = admin();
        long branchA = createBranch(admin);
        long branchB = createBranch(admin);
        Api.Resp self = api.post("/api/v1/auth/register", null, Map.of("email", unique("pref") + "@example.com",
                "password", "MemberPassw0rd", "firstName", "Pri", "lastName", "Ya", "phone", "+91 98765 43210",
                "homeBranchId", branchA));
        assertThat(self.status()).as(self.toString()).isEqualTo(201);
        String member = self.body().get("accessToken").asText();
        long memberId = self.body().get("user").get("memberId").asLong();
        long otherMember = createMember(admin, branchB);
        String path = "/api/v1/members/" + memberId + "/notification-preferences";

        Api.Resp mine = api.get(path, member);
        assertThat(mine.status()).as(mine.toString()).isEqualTo(200);
        assertThat(mine.body().get("smsNumber").asText()).isEqualTo("+919876543210");
        assertThat(channel(mine.body(), "MARKETING", "EMAIL").get("enabled").asBoolean()).isTrue();
        assertThat(channel(mine.body(), "MARKETING", "SMS").get("enabled").asBoolean()).isFalse();
        assertThat(channel(mine.body(), "BILLING", "EMAIL").get("locked").asBoolean()).isTrue();
        assertThat(channel(mine.body(), "BILLING", "SMS").get("enabled").asBoolean()).isTrue();
        assertThat(channel(mine.body(), "TRAINING", "SMS")).as("SMS not offered for training").isNull();

        // another member's preferences: forbidden
        assertThat(api.get("/api/v1/members/" + otherMember + "/notification-preferences", member).status()).isEqualTo(403);
        // staff of the member's branch may read and change; staff of another branch may not; trainers may only read
        String deskA = login(createStaff(admin, "RECEPTIONIST", branchA), STAFF_PASSWORD);
        String deskB = login(createStaff(admin, "RECEPTIONIST", branchB), STAFF_PASSWORD);
        String trainerA = login(createStaff(admin, "TRAINER", branchA), STAFF_PASSWORD);
        assertThat(api.get(path, deskA).status()).isEqualTo(200);
        assertThat(api.get(path, deskB).status()).isEqualTo(403);
        assertThat(put(path, deskB, Map.of("preferences", List.of(change("CLASSES", "EMAIL", false)))).status()).isEqualTo(403);
        assertThat(put(path, trainerA, Map.of("preferences", List.of(change("CLASSES", "EMAIL", false)))).status()).isEqualTo(403);
        assertThat(api.get(path, null).status()).isEqualTo(401);

        // locked and unavailable channels are rejected
        Api.Resp locked = put(path, member, Map.of("preferences", List.of(change("BILLING", "EMAIL", false))));
        assertThat(locked.status()).as(locked.toString()).isEqualTo(422);
        assertThat(locked.body().get("message").asText()).contains("cannot be switched off");
        assertThat(put(path, member, Map.of("preferences", List.of(change("TRAINING", "SMS", true)))).status()).isEqualTo(400);
        assertThat(put(path, member, Map.of("preferences", List.of())).status()).isEqualTo(400);

        Api.Resp updated = put(path, member, Map.of("preferences", List.of(change("MARKETING", "SMS", true),
                change("CLASSES", "EMAIL", false))));
        assertThat(updated.status()).as(updated.toString()).isEqualTo(200);
        assertThat(channel(updated.body(), "MARKETING", "SMS").get("enabled").asBoolean()).isTrue();
        assertThat(channel(updated.body(), "CLASSES", "EMAIL").get("enabled").asBoolean()).isFalse();
        Api.Resp byDesk = put(path, deskA, Map.of("preferences", List.of(change("CLASSES", "EMAIL", true))));
        assertThat(channel(byDesk.body(), "CLASSES", "EMAIL").get("enabled").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select source from member_notification_preferences where member_id = ? "
                + "and category = 'CLASSES' and channel = 'EMAIL'", String.class, memberId)).isEqualTo("STAFF");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where member_id = ? and action = "
                + "'NOTIFICATION_PREFERENCES_UPDATED'", Long.class, memberId)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ routing & dispatch

    @Test
    void optOutIsRespectedAtEnqueueAndAgainAtDispatch() {
        String admin = admin();
        long branch = createBranch(admin);
        long memberId = createMember(admin, branch);
        String path = "/api/v1/members/" + memberId + "/notification-preferences";

        notifyMember(memberId, NotificationType.CLASS_BOOKED);
        assertThat(row(memberId, NotificationType.CLASS_BOOKED, "EMAIL")).isNotNull();
        assertThat(row(memberId, NotificationType.CLASS_BOOKED, "SMS")).as("not an SMS type").isNull();

        assertThat(put(path, admin, Map.of("preferences", List.of(change("CLASSES", "EMAIL", false)))).status()).isEqualTo(200);
        notifyMember(memberId, NotificationType.CLASS_CANCELLED);
        assertThat(row(memberId, NotificationType.CLASS_CANCELLED, "EMAIL")).as("opted out: not enqueued").isNull();

        // waitlist promotion goes out by SMS too; the member switches SMS off before it is dispatched
        notifyMember(memberId, NotificationType.CLASS_WAITLIST_PROMOTED);
        Map<String, Object> sms = row(memberId, NotificationType.CLASS_WAITLIST_PROMOTED, "SMS");
        assertThat(sms).isNotNull();
        assertThat(sms.get("destination")).isEqualTo("+919000000000");
        assertThat(sms.get("body")).isEqualTo("Title CLASS_WAITLIST_PROMOTED: Body of CLASS_WAITLIST_PROMOTED");
        assertThat(put(path, admin, Map.of("preferences", List.of(change("CLASSES", "SMS", false)))).status()).isEqualTo(200);

        long smsId = ((Number) sms.get("id")).longValue();
        assertThat(dispatcher.dispatchNow(smsId)).isTrue();
        Map<String, Object> after = byId(smsId);
        assertThat(after.get("status")).isEqualTo("SKIPPED");
        assertThat((String) after.get("last_error")).contains("Opted out of Group classes by SMS");
        assertThat(after.get("attempts")).isEqualTo(0);
        assertThat(dispatcher.dispatchNow(smsId)).as("skipped rows are final").isFalse();
    }

    @Test
    void smsIsSentWithAProviderReceiptButSkippedWithoutAUsableMobileNumber() {
        String admin = admin();
        long branch = createBranch(admin);
        long withMobile = createMember(admin, branch);
        long landline = memberWithPhone(admin, branch, "+91 22 2345 6789");

        notifyMember(withMobile, NotificationType.PAYMENT_FAILED);
        long smsId = ((Number) row(withMobile, NotificationType.PAYMENT_FAILED, "SMS").get("id")).longValue();
        dispatcher.dispatchNow(smsId);
        Map<String, Object> sent = byId(smsId);
        assertThat(sent.get("status")).isEqualTo("SENT");
        assertThat(sent.get("provider")).isEqualTo("log");
        assertThat(sent.get("provider_message_id")).isEqualTo("log-" + smsId);
        assertThat(((Number) sent.get("segments")).intValue()).isEqualTo(1);

        notifyMember(landline, NotificationType.PAYMENT_FAILED);
        assertThat(row(landline, NotificationType.PAYMENT_FAILED, "EMAIL")).isNotNull();
        assertThat(row(landline, NotificationType.PAYMENT_FAILED, "SMS")).as("no usable phone: no SMS row").isNull();

        // a queued SMS whose number turns out unusable is SKIPPED, not retried until FAILED
        Long bad = jdbc.queryForObject("""
                insert into notifications (type, channel, member_id, destination, title, body, status)
                values ('PAYMENT_FAILED', 'SMS', ?, 'n/a', 'Hi', 'Body', 'PENDING') returning id
                """, Long.class, landline);
        dispatcher.dispatchNow(bad);
        Map<String, Object> skipped = byId(bad);
        assertThat(skipped.get("status")).isEqualTo("SKIPPED");
        assertThat((String) skipped.get("last_error")).contains("No usable mobile number");
    }

    @Test
    void mandatoryNoticesAreAlwaysEmailedEvenWhenOtherChannelsAreOff() {
        String admin = admin();
        long branch = createBranch(admin);
        long memberId = createMember(admin, branch);
        String path = "/api/v1/members/" + memberId + "/notification-preferences";
        assertThat(put(path, admin, Map.of("preferences", List.of(change("BILLING", "SMS", false)))).status()).isEqualTo(200);
        // even a stale / hand-written opt-out row cannot suppress the locked channel
        jdbc.update("insert into member_notification_preferences (member_id, category, channel, enabled, source) "
                + "values (?, 'BILLING', 'EMAIL', false, 'MEMBER')", memberId);

        notifyMember(memberId, NotificationType.AUTOPAY_SCHEDULED);   // RBI pre-debit notice
        Map<String, Object> email = row(memberId, NotificationType.AUTOPAY_SCHEDULED, "EMAIL");
        assertThat(email).as("pre-debit notice is mandatory by e-mail").isNotNull();
        assertThat(row(memberId, NotificationType.AUTOPAY_SCHEDULED, "SMS")).as("SMS switched off").isNull();
        long id = ((Number) email.get("id")).longValue();
        dispatcher.dispatchNow(id);
        assertThat(byId(id).get("status")).isEqualTo("SENT");
        assertThat(byId(id).get("provider")).isEqualTo("log");
    }

    @Test
    void promotionalSmsWaitsForQuietHoursToEndButTransactionalSmsDoesNot() {
        String admin = admin();
        long branch = createBranch(admin);
        long memberId = createMember(admin, branch);
        assertThat(put("/api/v1/members/" + memberId + "/notification-preferences", admin,
                Map.of("preferences", List.of(change("MARKETING", "SMS", true)))).status()).isEqualTo(200);

        ZonedDateTime tenPm = moveClockTo(22);
        Instant nineAm = LocalDate.from(tenPm).plusDays(1).atTime(9, 0).atZone(IST).toInstant();
        notifyMember(memberId, NotificationType.CAMPAIGN_MESSAGE);
        notifyMember(memberId, NotificationType.PAYMENT_FAILED);

        Map<String, Object> promo = row(memberId, NotificationType.CAMPAIGN_MESSAGE, "SMS");
        assertThat(((Timestamp) promo.get("next_attempt_at")).toInstant()).isEqualTo(nineAm);
        Map<String, Object> promoEmail = row(memberId, NotificationType.CAMPAIGN_MESSAGE, "EMAIL");
        assertThat(((Timestamp) promoEmail.get("next_attempt_at")).toInstant()).isBefore(tenPm.toInstant().plusSeconds(60));
        Map<String, Object> txn = row(memberId, NotificationType.PAYMENT_FAILED, "SMS");
        assertThat(((Timestamp) txn.get("next_attempt_at")).toInstant()).isBefore(tenPm.toInstant().plusSeconds(60));

        // even when forced at 23:00 the dispatcher defers it again without using up an attempt
        long promoId = ((Number) promo.get("id")).longValue();
        clock.advance(Duration.ofHours(1));
        dispatcher.dispatchNow(promoId);
        assertThat(byId(promoId).get("status")).isEqualTo("PENDING");
        assertThat(byId(promoId).get("attempts")).isEqualTo(0);
        assertThat(((Timestamp) byId(promoId).get("next_attempt_at")).toInstant()).isEqualTo(nineAm);

        clock.advance(Duration.ofHours(11));   // 10:00 next morning
        dispatcher.dispatchNow(promoId);
        assertThat(byId(promoId).get("status")).isEqualTo("SENT");
    }

    // ------------------------------------------------------------------ unsubscribe

    @Test
    void signedUnsubscribeLinkOptsOutOfMarketingEmailWithoutLogin() throws Exception {
        String admin = admin();
        long branch = createBranch(admin);
        long memberId = createMember(admin, branch);
        notifyMember(memberId, NotificationType.CAMPAIGN_MESSAGE);
        long queued = ((Number) row(memberId, NotificationType.CAMPAIGN_MESSAGE, "EMAIL").get("id")).longValue();
        String token = unsubscribeTokens.create(memberId, NotificationCategory.MARKETING, NotificationChannel.EMAIL);

        Api.Resp bad = api.post("/api/v1/public/notifications/unsubscribe", null, Map.of("token", token + "x"));
        assertThat(bad.status()).as(bad.toString()).isEqualTo(400);

        Api.Resp done = api.post("/api/v1/public/notifications/unsubscribe", null, Map.of("token", token));
        assertThat(done.status()).as(done.toString()).isEqualTo(200);
        assertThat(done.body().get("subscribed").asBoolean()).isFalse();
        assertThat(done.body().get("email").asText()).startsWith("m***@").endsWith("example.com");
        JsonNode prefs = api.get("/api/v1/members/" + memberId + "/notification-preferences", admin).body();
        assertThat(channel(prefs, "MARKETING", "EMAIL").get("enabled").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where member_id = ? and action = 'UNSUBSCRIBED'",
                Long.class, memberId)).isEqualTo(1);

        // the campaign e-mail queued before the click is not delivered; new campaign e-mails are not even queued
        dispatcher.dispatchNow(queued);
        assertThat(byId(queued).get("status")).isEqualTo("SKIPPED");
        notifyMember(memberId, NotificationType.CAMPAIGN_MESSAGE);
        assertThat(rows(memberId, NotificationType.CAMPAIGN_MESSAGE)).hasSize(1);

        // undo, then RFC 8058 one-click POST (form body, token in the query string) from a mail client
        Api.Resp undo = api.post("/api/v1/public/notifications/unsubscribe", null, Map.of("token", token, "resubscribe", true));
        assertThat(undo.body().get("subscribed").asBoolean()).isTrue();
        HttpResponse<String> oneClick = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/public/notifications/unsubscribe?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("List-Unsubscribe=One-Click")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(oneClick.statusCode()).as(oneClick.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select enabled from member_notification_preferences where member_id = ? "
                + "and category = 'MARKETING' and channel = 'EMAIL'", Boolean.class, memberId)).isFalse();
    }

    // ------------------------------------------------------------------ messaging centre

    @Test
    void testMessagesAreDeliveredImmediatelyAndScopedByRole() {
        String admin = admin();
        long branchA = createBranch(admin);
        long branchB = createBranch(admin);
        long memberA = createMember(admin, branchA);
        long memberB = createMember(admin, branchB);
        String managerA = login(createStaff(admin, "MANAGER", branchA), STAFF_PASSWORD);
        String deskA = login(createStaff(admin, "RECEPTIONIST", branchA), STAFF_PASSWORD);

        Api.Resp email = api.post("/api/v1/messaging/test", admin, Map.of("channel", "EMAIL", "to", "ops@example.com",
                "message", "Hello <b>team</b>"));
        assertThat(email.status()).as(email.toString()).isEqualTo(200);
        assertThat(email.body().get("status").asText()).isEqualTo("SENT");
        assertThat(email.body().get("type").asText()).isEqualTo("TEST_MESSAGE");
        assertThat(email.body().get("provider").asText()).isEqualTo("log");
        assertThat(email.body().get("requestedByUserId").isNull()).isFalse();

        Api.Resp sms = api.post("/api/v1/messaging/test", admin, Map.of("channel", "SMS", "to", "098765 43210"));
        assertThat(sms.status()).as(sms.toString()).isEqualTo(200);
        assertThat(sms.body().get("destination").asText()).isEqualTo("+919876543210");
        assertThat(sms.body().get("segments").asInt()).isEqualTo(1);
        assertThat(api.post("/api/v1/messaging/test", admin, Map.of("channel", "SMS", "to", "12")).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/messaging/test", admin, Map.of("channel", "IN_APP", "to", "x")).status()).isEqualTo(400);

        // branch managers test against members of their branches only, never arbitrary addresses
        assertThat(api.post("/api/v1/messaging/test", managerA, Map.of("channel", "EMAIL", "to", "x@example.com")).status())
                .isEqualTo(403);
        Api.Resp toMember = api.post("/api/v1/messaging/test", managerA, Map.of("channel", "SMS", "memberId", memberA));
        assertThat(toMember.status()).as(toMember.toString()).isEqualTo(200);
        assertThat(toMember.body().get("memberId").asLong()).isEqualTo(memberA);
        assertThat(api.post("/api/v1/messaging/test", managerA, Map.of("channel", "SMS", "memberId", memberB)).status())
                .isEqualTo(403);
        assertThat(api.post("/api/v1/messaging/test", deskA, Map.of("channel", "SMS", "memberId", memberA)).status())
                .as("receptionists cannot use the messaging centre").isEqualTo(403);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where entity_type = 'NOTIFICATION' and action = 'TEST_SENT' "
                + "and entity_id = ?", Long.class, email.id())).isEqualTo(1);

        Api.Resp providers = api.get("/api/v1/messaging/providers", managerA);
        assertThat(providers.status()).isEqualTo(200);
        assertThat(providers.body().get("email").get("provider").asText()).isEqualTo("log");
        assertThat(providers.body().get("sms").get("provider").asText()).isEqualTo("log");
        assertThat(providers.body().get("sms").get("configured").asBoolean()).isTrue();
        assertThat(providers.raw().body()).doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("secret");
        assertThat(api.get("/api/v1/messaging/providers", deskA).status()).isEqualTo(403);
    }

    @Test
    void failedNotificationsCanBeSearchedAndResentToTheCurrentAddress() {
        String admin = admin();
        long branchA = createBranch(admin);
        long branchB = createBranch(admin);
        long memberId = createMember(admin, branchA);
        String currentEmail = api.get("/api/v1/members/" + memberId, admin).body().get("email").asText();
        String managerA = login(createStaff(admin, "MANAGER", branchA), STAFF_PASSWORD);
        String managerB = login(createStaff(admin, "MANAGER", branchB), STAFF_PASSWORD);
        Long failedId = jdbc.queryForObject("""
                insert into notifications (type, channel, member_id, destination, title, body, status, attempts, last_error)
                values ('RENEWAL_REMINDER', 'EMAIL', ?, 'old-address@example.com', 'Renew soon', 'Body', 'FAILED', 5,
                        'SMTP delivery failed: Connection refused') returning id
                """, Long.class, memberId);

        Api.Resp search = api.get("/api/v1/messaging/notifications?status=FAILED&channel=EMAIL&type=RENEWAL_REMINDER&memberId="
                + memberId + "&q=old-address&from=" + LocalDate.now(IST).minusDays(1) + "&to=" + LocalDate.now(IST).plusDays(1), managerA);
        assertThat(search.status()).as(search.toString()).isEqualTo(200);
        assertThat(search.body().get("totalElements").asLong()).isEqualTo(1);
        JsonNode entry = search.body().get("content").get(0);
        assertThat(entry.get("id").asLong()).isEqualTo(failedId);
        assertThat(entry.get("category").asText()).isEqualTo("MEMBERSHIP");
        assertThat(entry.get("memberName").asText()).isEqualTo("Test Member");
        Api.Resp otherBranch = api.get("/api/v1/messaging/notifications?memberId=" + memberId, managerB);
        assertThat(otherBranch.body().get("totalElements").asLong()).as("branch-scoped").isZero();
        assertThat(api.get("/api/v1/messaging/notifications/" + failedId, managerB).status()).isEqualTo(404);
        assertThat(api.post("/api/v1/messaging/notifications/" + failedId + "/resend", managerB, null).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/messaging/notifications?size=500", admin).status()).isEqualTo(400);

        Api.Resp resent = api.post("/api/v1/messaging/notifications/" + failedId + "/resend", managerA, null);
        assertThat(resent.status()).as(resent.toString()).isEqualTo(200);
        assertThat(resent.body().get("status").asText()).isEqualTo("SENT");
        assertThat(resent.body().get("destination").asText()).isEqualTo(currentEmail);
        assertThat(resent.body().get("attempts").asInt()).isEqualTo(1);
        assertThat(resent.body().get("lastError").isNull()).isTrue();
        assertThat(api.post("/api/v1/messaging/notifications/" + failedId + "/resend", managerA, null).status())
                .as("only FAILED can be resent").isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where entity_type = 'NOTIFICATION' and action = 'RESENT' "
                + "and entity_id = ?", Long.class, failedId)).isEqualTo(1);
    }
}
