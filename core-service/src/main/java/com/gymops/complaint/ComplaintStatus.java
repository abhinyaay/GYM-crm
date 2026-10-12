package com.gymops.complaint;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Ticket state machine; {@link #canTransitionTo} is the single source of truth for allowed moves. */
public enum ComplaintStatus {
    OPEN,
    IN_PROGRESS,
    ESCALATED,
    RESOLVED,
    CLOSED,
    REOPENED;

    private static final Map<ComplaintStatus, Set<ComplaintStatus>> TRANSITIONS = Map.of(
            OPEN, EnumSet.of(IN_PROGRESS, ESCALATED, RESOLVED),
            IN_PROGRESS, EnumSet.of(ESCALATED, RESOLVED),
            ESCALATED, EnumSet.of(IN_PROGRESS, RESOLVED),
            REOPENED, EnumSet.of(IN_PROGRESS, ESCALATED, RESOLVED),
            RESOLVED, EnumSet.of(CLOSED, REOPENED),
            CLOSED, EnumSet.noneOf(ComplaintStatus.class));

    public static final Set<ComplaintStatus> UNRESOLVED = EnumSet.of(OPEN, IN_PROGRESS, ESCALATED, REOPENED);

    public boolean canTransitionTo(ComplaintStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }
}
