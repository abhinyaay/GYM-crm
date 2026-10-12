package com.gymops.growth.referral;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Pure referral rules (no I/O), unit tested: code format and generation, fraud guards, the
 * monthly-cap window and the hold period.
 */
public final class ReferralRules {

    /** Crockford-style alphabet without look-alikes (0/O, 1/I/L, U): easy to read out at the desk. */
    static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final String FALLBACK_PREFIX = "GYM";
    private static final int MAX_PREFIX = 4;
    private static final int PHONE_TAIL = 10;

    /** Identity of one side of a referral for the self-referral check. */
    public record Party(Long memberId, String email, String phone) {
    }

    private ReferralRules() {
    }

    // ------------------------------------------------------------------ codes

    /**
     * A referral code such as {@code ASHA7K2}: up to four letters of the first name (accents
     * stripped, non-Latin names fall back to {@code GYM}) plus {@code suffixLength} random characters.
     */
    public static String generateCode(String firstName, int suffixLength, RandomGenerator random) {
        String prefix = codePrefix(firstName);
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < suffixLength; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    static String codePrefix(String firstName) {
        String letters = firstName == null ? "" : Normalizer.normalize(firstName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        if (letters.length() < 2) {
            return FALLBACK_PREFIX;
        }
        return letters.substring(0, Math.min(MAX_PREFIX, letters.length()));
    }

    /** Canonical form of user-typed codes ("asha-7k2 " -> "ASHA7K2"); empty when it cannot be a code. */
    public static Optional<String> normalizeCode(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String code = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        return code.matches("^[A-Z0-9]{5,16}$") ? Optional.of(code) : Optional.empty();
    }

    // ------------------------------------------------------------------ fraud guards

    /** Same member, same email (case-insensitive) or same phone (last 10 digits) = self-referral. */
    public static boolean isSelfReferral(Party referrer, Party referee) {
        if (referrer.memberId() != null && Objects.equals(referrer.memberId(), referee.memberId())) {
            return true;
        }
        String e1 = normalizeEmail(referrer.email());
        if (e1 != null && e1.equals(normalizeEmail(referee.email()))) {
            return true;
        }
        String p1 = phoneTail(referrer.phone());
        return p1 != null && p1.equals(phoneTail(referee.phone()));
    }

    /**
     * The referee counts as new when they had no paid membership before the referral, allowing a short
     * grace for codes attached at the desk right after the first purchase.
     */
    public static boolean isNewCustomer(Instant firstPaidActivation, Instant referralCreatedAt, int graceDays) {
        return firstPaidActivation == null
                || !firstPaidActivation.isBefore(referralCreatedAt.minus(Duration.ofDays(graceDays)));
    }

    public static boolean capReached(long referrerRewardsThisMonth, int monthlyCap) {
        return referrerRewardsThisMonth >= monthlyCap;
    }

    /** [start, end) of the business-timezone calendar month containing {@code now}. */
    public static Instant[] monthWindow(Instant now, ZoneId zone) {
        YearMonth month = YearMonth.from(now.atZone(zone));
        return new Instant[]{month.atDay(1).atStartOfDay(zone).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()};
    }

    public static Instant holdUntil(Instant convertedAt, int holdDays) {
        return convertedAt.plus(Duration.ofDays(holdDays));
    }

    // ------------------------------------------------------------------ helpers

    /** "Rahul Sharma" -> "Rahul S." (referrers never see referees' full names or contact details). */
    public static String displayName(String firstName, String lastName) {
        if (lastName == null || lastName.isBlank()) {
            return firstName;
        }
        return firstName + " " + lastName.strip().substring(0, 1).toUpperCase(Locale.ROOT) + ".";
    }

    public static String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Last ten digits of a phone number, so "+91 98765 43210" and "9876543210" compare equal. */
    public static String phoneTail(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 7) {
            return null;
        }
        return digits.length() > PHONE_TAIL ? digits.substring(digits.length() - PHONE_TAIL) : digits;
    }
}
