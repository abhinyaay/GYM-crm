package com.gymops.pricing.gst;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * GSTIN (15 characters): 2-digit state code, 10-character PAN, entity number (1-9, A-Z), 'Z', and a
 * mod-36 check character. {@link #isValid} checks format, state code and checksum.
 */
public final class Gstin {

    private static final Pattern FORMAT = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private Gstin() {
    }

    public static String normalize(String gstin) {
        return gstin == null ? null : gstin.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String gstin) {
        String g = normalize(gstin);
        if (g == null || !FORMAT.matcher(g).matches() || !IndianStates.isValidCode(g.substring(0, 2))) {
            return false;
        }
        return checkChar(g.substring(0, 14)) == g.charAt(14);
    }

    /** Check character for the first 14 characters (GSTN mod-36 algorithm). */
    public static char checkChar(String first14) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int value = CHARS.indexOf(first14.charAt(i));
            int product = value * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        return CHARS.charAt((36 - sum % 36) % 36);
    }

    public static String stateCode(String gstin) {
        return normalize(gstin).substring(0, 2);
    }
}
