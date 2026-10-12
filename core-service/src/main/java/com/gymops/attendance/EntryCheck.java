package com.gymops.attendance;

import com.gymops.subscription.Subscription;
import java.util.Objects;

/**
 * Result of evaluating the entry rules for one member at one branch and instant. Exactly one of
 * {@code subscription} (admitted, the subscription that grants access) or {@code denial} is set.
 */
public record EntryCheck(Subscription subscription, EntryDenial denial, String message) {

    public static EntryCheck allowed(Subscription subscription) {
        return new EntryCheck(Objects.requireNonNull(subscription), null, null);
    }

    public static EntryCheck denied(EntryDenial denial, String message) {
        return new EntryCheck(null, Objects.requireNonNull(denial), message);
    }

    public boolean isAllowed() {
        return denial == null;
    }

    /**
     * Which checks to run. The front desk runs everything; a gate handles "already inside" itself
     * (anti-passback); a late offline event is judged retrospectively, without live occupancy.
     */
    public record Options(boolean checkOpenVisit, boolean checkCapacity) {
        public static final Options DESK = new Options(true, true);
        public static final Options GATE_LIVE = new Options(false, true);
        public static final Options RETROSPECTIVE = new Options(false, false);
    }
}
