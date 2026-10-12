package com.gymops.pricing.gst;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Indian-system amount in words: "Indian Rupees One Lakh Two Thousand and Fifty Paise Only". */
public final class AmountInWords {

    private static final String[] ONES = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
        "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty",
        "Ninety"};

    private AmountInWords() {
    }

    public static String rupees(BigDecimal amount) {
        BigDecimal a = amount.abs().setScale(2, RoundingMode.HALF_UP);
        long rupees = a.longValue();
        int paise = a.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        StringBuilder sb = new StringBuilder("Indian Rupees ");
        sb.append(rupees == 0 ? "Zero" : words(rupees));
        if (paise > 0) {
            sb.append(" and ").append(words(paise)).append(" Paise");
        }
        return sb.append(" Only").toString();
    }

    /** Words for a non-negative whole number in the Indian system (thousand, lakh, crore). */
    public static String words(long n) {
        if (n == 0) {
            return "Zero";
        }
        StringBuilder sb = new StringBuilder();
        long crore = n / 10_000_000;
        n %= 10_000_000;
        if (crore > 0) {
            sb.append(words(crore)).append(" Crore ");
        }
        long lakh = n / 100_000;
        n %= 100_000;
        if (lakh > 0) {
            sb.append(belowHundred((int) lakh)).append(" Lakh ");
        }
        long thousand = n / 1000;
        n %= 1000;
        if (thousand > 0) {
            sb.append(belowHundred((int) thousand)).append(" Thousand ");
        }
        long hundred = n / 100;
        n %= 100;
        if (hundred > 0) {
            sb.append(ONES[(int) hundred]).append(" Hundred ");
        }
        if (n > 0) {
            sb.append(belowHundred((int) n));
        }
        return sb.toString().trim();
    }

    private static String belowHundred(int n) {
        if (n < 20) {
            return ONES[n];
        }
        return (TENS[n / 10] + " " + ONES[n % 10]).trim();
    }
}
