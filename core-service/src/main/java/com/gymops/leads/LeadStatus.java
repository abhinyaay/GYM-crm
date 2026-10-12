package com.gymops.leads;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Sales pipeline state machine; {@link #canTransitionTo} is the single source of truth.
 *
 * <pre>
 *   NEW -> CONTACTED -> TOUR_SCHEDULED -> TRIAL_ACTIVE -> CONVERTED
 *   (stages may be skipped forwards, e.g. a walk-in starts a trial straight from NEW,
 *    and a prospect who buys a membership converts from any open stage)
 *   any open stage -> LOST (with a reason);  LOST -> NEW (reopen);  CONVERTED is final
 * </pre>
 *
 * TRIAL_ACTIVE is reached only by starting a trial and CONVERTED only by linking a paying member,
 * so {@link #MANUAL_TARGETS} lists what a plain status change may request.
 */
public enum LeadStatus {
    NEW,
    CONTACTED,
    TOUR_SCHEDULED,
    TRIAL_ACTIVE,
    CONVERTED,
    LOST;

    private static final Map<LeadStatus, Set<LeadStatus>> TRANSITIONS = Map.of(
            NEW, EnumSet.of(CONTACTED, TOUR_SCHEDULED, TRIAL_ACTIVE, CONVERTED, LOST),
            CONTACTED, EnumSet.of(TOUR_SCHEDULED, TRIAL_ACTIVE, CONVERTED, LOST),
            TOUR_SCHEDULED, EnumSet.of(TRIAL_ACTIVE, CONVERTED, LOST),
            TRIAL_ACTIVE, EnumSet.of(CONVERTED, LOST),
            CONVERTED, EnumSet.noneOf(LeadStatus.class),
            LOST, EnumSet.of(NEW));

    /** Pipeline stages a lead is still being worked in (matches the partial unique index on phone). */
    public static final Set<LeadStatus> OPEN = EnumSet.of(NEW, CONTACTED, TOUR_SCHEDULED, TRIAL_ACTIVE);

    /** Targets reachable through the generic status endpoint (the rest have dedicated workflows). */
    public static final Set<LeadStatus> MANUAL_TARGETS = EnumSet.of(NEW, CONTACTED, TOUR_SCHEDULED, LOST);

    public boolean canTransitionTo(LeadStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    /** Statuses a user can pick in a plain status change from this status. */
    public Set<LeadStatus> manualTargets() {
        Set<LeadStatus> targets = EnumSet.noneOf(LeadStatus.class);
        TRANSITIONS.get(this).stream().filter(MANUAL_TARGETS::contains).forEach(targets::add);
        return targets;
    }
}
