package com.gymops.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Monetary helpers. All amounts are BigDecimal with scale 2 and HALF_UP rounding (never double).
 */
public final class Money {

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private Money() {
    }

    public static BigDecimal of(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal of(String value) {
        return of(new BigDecimal(value));
    }

    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    public static boolean isPositive(BigDecimal value) {
        return value.signum() > 0;
    }
}
