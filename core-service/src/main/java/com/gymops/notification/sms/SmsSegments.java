package com.gymops.notification.sms;

/**
 * Estimates billable SMS segments. GSM-7 text: 160 characters in one segment, 153 per segment when concatenated
 * (extension characters such as {@code []{}~^|€} count twice). Anything else is sent as UCS-2: 70 / 67.
 */
public final class SmsSegments {

    private static final String GSM_BASIC = "@£$¥èéùìòÇ\nØø\rÅå"
            + "Δ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./"
            + "0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyz"
            + "äöñüà";
    private static final String GSM_EXTENDED = "^{}\\[~]|€\f";

    private SmsSegments() {
    }

    public static boolean isGsm7(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (GSM_BASIC.indexOf(c) < 0 && GSM_EXTENDED.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    public static int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (isGsm7(text)) {
            int septets = 0;
            for (int i = 0; i < text.length(); i++) {
                septets += GSM_EXTENDED.indexOf(text.charAt(i)) >= 0 ? 2 : 1;
            }
            return septets <= 160 ? 1 : (septets + 152) / 153;
        }
        int units = text.length();   // UTF-16 code units = UCS-2 characters on the wire
        return units <= 70 ? 1 : (units + 66) / 67;
    }
}
