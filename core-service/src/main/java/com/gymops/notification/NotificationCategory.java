package com.gymops.notification;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a notification is about. Members choose EMAIL / SMS per category; IN_APP is always on.
 *
 * <ul>
 *   <li><b>Locked channels</b> cannot be switched off because the category carries legal or security notices
 *       (payment receipts, RBI e-mandate pre-debit notices, consent confirmations). At least one channel of
 *       those messages therefore always reaches the member.</li>
 *   <li><b>SMS</b> is offered only for categories that contain SMS-worthy types (see {@link #smsByDefault(NotificationType)});
 *       elsewhere the toggle would have no effect.</li>
 * </ul>
 */
public enum NotificationCategory {
    ACCOUNT("Account & privacy", "Security, consent and account notices", Set.of(NotificationChannel.EMAIL), false, false),
    BILLING("Payments & billing", "Receipts, autopay pre-debit notices, failed payments and refunds",
            Set.of(NotificationChannel.EMAIL), true, true),
    MEMBERSHIP("Membership & rewards", "Activation, renewals, expiry warnings, freezes, trials and referral rewards",
            Set.of(), true, true),
    CLASSES("Group classes", "Bookings, waitlist promotions, cancellations and reminders", Set.of(), true, true),
    TRAINING("Personal training", "Trainer assignment, sessions and workout plans", Set.of(), false, false),
    SUPPORT("Support requests", "Updates on complaints and requests you raised", Set.of(), false, false),
    ACCESS("Gate access & visits", "Entry credentials, automatic check-outs and attendance milestones", Set.of(), true, true),
    MARKETING("Offers & news", "Promotions, campaigns and gym news", Set.of(), true, false);

    /** Types that go out by SMS by default (when the member's phone is usable and the category's SMS toggle is on). */
    private static final Set<NotificationType> SMS_TYPES = EnumSet.of(
            NotificationType.PAYMENT_FAILED,
            NotificationType.AUTOPAY_SCHEDULED,
            NotificationType.AUTOPAY_FAILED,
            NotificationType.AUTOPAY_DISABLED,
            NotificationType.MEMBERSHIP_EXPIRY_WARNING,
            NotificationType.MEMBERSHIP_EXPIRED,
            NotificationType.CLASS_WAITLIST_PROMOTED,
            NotificationType.TRIAL_ENDING,
            NotificationType.ACCESS_CREDENTIAL_ENROLLED,
            NotificationType.CAMPAIGN_MESSAGE);

    private final String label;
    private final String description;
    private final Set<NotificationChannel> lockedChannels;
    private final boolean smsAvailable;
    private final boolean smsDefault;

    NotificationCategory(String label, String description, Set<NotificationChannel> lockedChannels, boolean smsAvailable,
                         boolean smsDefault) {
        this.label = label;
        this.description = description;
        this.lockedChannels = lockedChannels;
        this.smsAvailable = smsAvailable;
        this.smsDefault = smsDefault;
    }

    /** Category of a notification type. The {@code default} branch keeps newly added types working (ACCOUNT). */
    public static NotificationCategory of(NotificationType type) {
        return switch (type) {
            case PAYMENT_CONFIRMATION, PAYMENT_FAILED, REFUND_PROCESSED, AUTOPAY_SCHEDULED, AUTOPAY_SUCCEEDED, AUTOPAY_FAILED,
                 AUTOPAY_DISABLED, PAYMENT_METHOD_SAVED, STORE_CREDIT_ISSUED, PLAN_PRICE_CHANGED -> BILLING;
            case SUBSCRIPTION_ACTIVATED, SUBSCRIPTION_FROZEN, SUBSCRIPTION_RESUMED, SUBSCRIPTION_CANCELLED, MEMBERSHIP_EXPIRED,
                 RENEWAL_REMINDER, MEMBERSHIP_EXPIRY_WARNING, TRIAL_STARTED, TRIAL_ENDING, REFERRAL_SIGNED_UP,
                 REFERRAL_REWARD_EARNED -> MEMBERSHIP;
            case CLASS_BOOKED, CLASS_WAITLISTED, CLASS_WAITLIST_PROMOTED, CLASS_CANCELLED, CLASS_RESCHEDULED,
                 CLASS_REMINDER -> CLASSES;
            case TRAINER_ASSIGNED, SESSION_SCHEDULED, WORKOUT_PLAN_ASSIGNED, WORKOUT_PLAN_UPDATED -> TRAINING;
            case COMPLAINT_UPDATED, COMPLAINT_ESCALATED -> SUPPORT;
            case ACCESS_CREDENTIAL_ENROLLED, ACCESS_DEVICE_OFFLINE, ACCESS_ALARM, ATTENDANCE_MILESTONE, VISIT_AUTO_CLOSED -> ACCESS;
            case CAMPAIGN_MESSAGE -> MARKETING;
            default -> ACCOUNT;   // TEST_MESSAGE, BIOMETRIC_CONSENT_WITHDRAWN, staff-only types and anything added later
        };
    }

    /** True when the type should also go out by SMS unless the member switched SMS off for its category. */
    public static boolean smsByDefault(NotificationType type) {
        return SMS_TYPES.contains(type);
    }

    /** Test messages are explicit operator actions: they ignore preferences and quiet hours. */
    public static boolean bypassesPreferences(NotificationType type) {
        return type == NotificationType.TEST_MESSAGE;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public boolean isLocked(NotificationChannel channel) {
        return lockedChannels.contains(channel);
    }

    /** Whether members can choose this channel for the category at all. */
    public boolean offers(NotificationChannel channel) {
        return switch (channel) {
            case EMAIL -> true;
            case SMS -> smsAvailable;
            default -> false;
        };
    }

    /** Value used when the member has not expressed a preference. MARKETING: email on, SMS off. */
    public boolean defaultEnabled(NotificationChannel channel) {
        return switch (channel) {
            case EMAIL -> true;
            case SMS -> smsAvailable && smsDefault;
            default -> false;
        };
    }

    /** Only promotional messages are subject to quiet hours (TRAI); transactional ones are never delayed. */
    public boolean isPromotional() {
        return this == MARKETING;
    }

    /** Human readable reason shown next to a locked toggle. */
    public String lockedReason() {
        return switch (this) {
            case BILLING -> "Payment receipts and autopay pre-debit notices are required by law and are always emailed.";
            case ACCOUNT -> "Security and consent notices are always emailed.";
            default -> null;
        };
    }

    public static Set<NotificationCategory> all() {
        return EnumSet.allOf(NotificationCategory.class);
    }
}
