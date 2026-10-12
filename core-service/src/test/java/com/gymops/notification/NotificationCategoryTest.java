package com.gymops.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymops.notification.preference.MemberPreferences;
import com.gymops.notification.preference.NotificationPreference;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationCategoryTest {

    @Test
    void everyTypeHasACategory() {
        for (NotificationType type : NotificationType.values()) {
            assertThat(NotificationCategory.of(type)).as(type.name()).isNotNull();
        }
    }

    @Test
    void mapsTypesToTheirCategories() {
        assertThat(NotificationCategory.of(NotificationType.AUTOPAY_SCHEDULED)).isEqualTo(NotificationCategory.BILLING);
        assertThat(NotificationCategory.of(NotificationType.PAYMENT_CONFIRMATION)).isEqualTo(NotificationCategory.BILLING);
        assertThat(NotificationCategory.of(NotificationType.MEMBERSHIP_EXPIRY_WARNING)).isEqualTo(NotificationCategory.MEMBERSHIP);
        assertThat(NotificationCategory.of(NotificationType.CLASS_WAITLIST_PROMOTED)).isEqualTo(NotificationCategory.CLASSES);
        assertThat(NotificationCategory.of(NotificationType.WORKOUT_PLAN_ASSIGNED)).isEqualTo(NotificationCategory.TRAINING);
        assertThat(NotificationCategory.of(NotificationType.COMPLAINT_UPDATED)).isEqualTo(NotificationCategory.SUPPORT);
        assertThat(NotificationCategory.of(NotificationType.ACCESS_CREDENTIAL_ENROLLED)).isEqualTo(NotificationCategory.ACCESS);
        assertThat(NotificationCategory.of(NotificationType.CAMPAIGN_MESSAGE)).isEqualTo(NotificationCategory.MARKETING);
        assertThat(NotificationCategory.of(NotificationType.TEST_MESSAGE)).isEqualTo(NotificationCategory.ACCOUNT);
        assertThat(NotificationCategory.of(NotificationType.LEAD_ASSIGNED)).as("default branch").isEqualTo(NotificationCategory.ACCOUNT);
    }

    @Test
    void smsGoesOnlyToTimeCriticalTypes() {
        assertThat(NotificationCategory.smsByDefault(NotificationType.PAYMENT_FAILED)).isTrue();
        assertThat(NotificationCategory.smsByDefault(NotificationType.AUTOPAY_SCHEDULED)).isTrue();
        assertThat(NotificationCategory.smsByDefault(NotificationType.MEMBERSHIP_EXPIRED)).isTrue();
        assertThat(NotificationCategory.smsByDefault(NotificationType.CLASS_WAITLIST_PROMOTED)).isTrue();
        assertThat(NotificationCategory.smsByDefault(NotificationType.TRIAL_ENDING)).isTrue();
        assertThat(NotificationCategory.smsByDefault(NotificationType.CLASS_BOOKED)).isFalse();
        assertThat(NotificationCategory.smsByDefault(NotificationType.PAYMENT_CONFIRMATION)).isFalse();
        // every SMS-by-default type lives in a category that offers SMS
        for (NotificationType type : NotificationType.values()) {
            if (NotificationCategory.smsByDefault(type)) {
                assertThat(NotificationCategory.of(type).offers(NotificationChannel.SMS)).as(type.name()).isTrue();
            }
        }
    }

    @Test
    void marketingDefaultsToEmailOnSmsOffAndIsPromotional() {
        NotificationCategory m = NotificationCategory.MARKETING;
        assertThat(m.defaultEnabled(NotificationChannel.EMAIL)).isTrue();
        assertThat(m.defaultEnabled(NotificationChannel.SMS)).isFalse();
        assertThat(m.isPromotional()).isTrue();
        assertThat(NotificationCategory.BILLING.isPromotional()).isFalse();
    }

    @Test
    void lockedChannelsIgnoreAnOptOutButOthersRespectIt() throws Exception {
        MemberPreferences prefs = MemberPreferences.of(List.of(
                pref(NotificationCategory.BILLING, NotificationChannel.EMAIL, false),
                pref(NotificationCategory.BILLING, NotificationChannel.SMS, false),
                pref(NotificationCategory.MARKETING, NotificationChannel.SMS, true)));
        assertThat(prefs.allows(NotificationType.AUTOPAY_SCHEDULED, NotificationChannel.EMAIL)).as("pre-debit notice locked").isTrue();
        assertThat(prefs.allows(NotificationType.AUTOPAY_SCHEDULED, NotificationChannel.SMS)).isFalse();
        assertThat(prefs.allows(NotificationType.CAMPAIGN_MESSAGE, NotificationChannel.SMS)).isTrue();
        assertThat(prefs.allows(NotificationType.CLASS_BOOKED, NotificationChannel.SMS)).as("default on").isTrue();
        assertThat(prefs.allows(NotificationType.WORKOUT_PLAN_ASSIGNED, NotificationChannel.SMS)).as("not offered").isFalse();
        assertThat(prefs.allows(NotificationType.TEST_MESSAGE, NotificationChannel.SMS)).as("test bypasses").isTrue();
        assertThat(MemberPreferences.defaults().allows(NotificationType.CAMPAIGN_MESSAGE, NotificationChannel.SMS)).isFalse();
        assertThat(MemberPreferences.defaults().allows(NotificationType.CAMPAIGN_MESSAGE, NotificationChannel.EMAIL)).isTrue();
    }

    private static NotificationPreference pref(NotificationCategory c, NotificationChannel ch, boolean enabled) throws Exception {
        Constructor<NotificationPreference> ctor = NotificationPreference.class.getConstructor(Long.class,
                NotificationCategory.class, NotificationChannel.class);
        NotificationPreference p = ctor.newInstance(1L, c, ch);
        p.set(enabled, NotificationPreference.Source.MEMBER, null, Instant.now());
        return p;
    }
}
