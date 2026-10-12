package com.gymops.access;

/** How people leave a branch; chosen per branch by the gym owner. */
public enum ExitMode {
    /** Free exit: only entry is verified; people leave via a push button / exit side. Visits close automatically. */
    BUTTON,
    /** Members scan again to leave; anti-passback applies (no second entry without an exit). */
    VERIFIED
}
