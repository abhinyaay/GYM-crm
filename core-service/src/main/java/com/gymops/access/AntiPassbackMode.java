package com.gymops.access;

/**
 * HARD: a second entry without an exit (or an exit without being inside) is denied.
 * SOFT: the gate opens but the event is flagged; on entry the stale open visit is closed and a new one opened.
 */
public enum AntiPassbackMode {
    HARD,
    SOFT
}
