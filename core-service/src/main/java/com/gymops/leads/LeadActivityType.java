package com.gymops.leads;

import java.util.EnumSet;
import java.util.Set;

public enum LeadActivityType {
    NOTE,
    CALL,
    EMAIL,
    WHATSAPP,
    SMS,
    VISIT,
    TOUR,
    STATUS_CHANGE,
    FOLLOW_UP;

    /** Activities that count as actually reaching the prospect (moves a NEW lead to CONTACTED). */
    public static final Set<LeadActivityType> CONTACT = EnumSet.of(CALL, EMAIL, WHATSAPP, SMS, VISIT, TOUR);

    /** Types staff may log directly; STATUS_CHANGE and FOLLOW_UP are written by the workflow itself. */
    public static final Set<LeadActivityType> LOGGABLE = EnumSet.of(NOTE, CALL, EMAIL, WHATSAPP, SMS, VISIT, TOUR);

    public boolean isContact() {
        return CONTACT.contains(this);
    }
}
