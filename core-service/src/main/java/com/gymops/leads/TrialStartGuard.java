package com.gymops.leads;

import java.util.Objects;

/**
 * Marks the member whose trial subscription is being created on the current thread. Starting a free
 * trial activates its subscription inside the same transaction, which publishes
 * {@code SubscriptionActivated}; the auto-conversion listener must not mistake that for a purchase.
 */
final class TrialStartGuard {

    private static final ThreadLocal<Long> MEMBER = new ThreadLocal<>();

    private TrialStartGuard() {
    }

    static void begin(Long memberId) {
        MEMBER.set(memberId);
    }

    static void end() {
        MEMBER.remove();
    }

    static boolean isStartingTrialFor(Long memberId) {
        return Objects.equals(MEMBER.get(), memberId);
    }
}
