package com.gymops.notification.sms;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalises free-form phone numbers to E.164 ({@code +<country code><number>}), the format SMS providers expect.
 * Numbers without a country code get the configured default (91 = India). Indian numbers must be 10-digit
 * mobiles (starting 6-9); landlines and junk yield {@code Optional.empty()} so the SMS is skipped, not failed.
 */
public final class PhoneNumbers {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-().\\u00A0/]");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern INDIAN_MOBILE = Pattern.compile("[6-9]\\d{9}");

    private PhoneNumbers() {
    }

    public static Optional<String> toE164(String raw, String defaultCountryCode) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String s = SEPARATORS.matcher(raw.trim()).replaceAll("");
        String digits;
        if (s.startsWith("+")) {
            digits = s.substring(1);
        } else if (s.startsWith("00")) {
            digits = s.substring(2);
        } else {
            String national = s.replaceFirst("^0+", "");            // trunk prefix, e.g. 09876543210
            boolean hasCountryCode = national.startsWith(defaultCountryCode)
                    && national.length() - defaultCountryCode.length() >= 10;
            digits = hasCountryCode ? national : defaultCountryCode + national;
        }
        if (!DIGITS.matcher(digits).matches() || digits.length() < 8 || digits.length() > 15 || digits.charAt(0) == '0') {
            return Optional.empty();
        }
        if (digits.startsWith("91") && !INDIAN_MOBILE.matcher(digits.substring(2)).matches()) {
            return Optional.empty();
        }
        return Optional.of("+" + digits);
    }

    /** "+919876543210" -> "+91******3210" (for logs and admin screens that do not need the full number). */
    public static String mask(String e164) {
        if (e164 == null || e164.length() < 7) {
            return "***";
        }
        return e164.substring(0, 3) + "*".repeat(e164.length() - 7) + e164.substring(e164.length() - 4);
    }
}
