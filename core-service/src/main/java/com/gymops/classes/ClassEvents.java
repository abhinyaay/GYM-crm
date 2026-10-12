package com.gymops.classes;

import java.util.List;

/**
 * Group-class domain events, published inside the business transaction and turned into outbox
 * notifications by {@link ClassNotificationHandler} (so notifications commit atomically with the change).
 */
public final class ClassEvents {

    private ClassEvents() {
    }

    public record ClassBooked(Long bookingId) {
    }

    public record ClassWaitlisted(Long bookingId, int position) {
    }

    public record WaitlistPromoted(Long bookingId) {
    }

    public record SessionCancelled(Long sessionId, List<Long> memberIds) {
    }

    public record SessionRescheduled(Long sessionId, List<Long> memberIds) {
    }

    public record ReminderDue(Long bookingId) {
    }
}
