package com.gymops.growth.referral;

import com.gymops.payment.DiscountType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Human-readable reward and offer wording shared by notifications, the member page and the public lookup. */
public final class RewardTexts {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private RewardTexts() {
    }

    public static String rupees(BigDecimal amount) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        return "₹" + (scaled.stripTrailingZeros().scale() <= 0 ? scaled.setScale(0, RoundingMode.UNNECESSARY).toPlainString()
                : scaled.toPlainString());
    }

    public static String discount(DiscountType type, BigDecimal value) {
        return type == DiscountType.PERCENTAGE
                ? value.stripTrailingZeros().toPlainString() + "% off" : rupees(value) + " off";
    }

    /** "₹500 store credit" / "10% off your next membership purchase". */
    public static String reward(RewardType type, DiscountType discountType, BigDecimal value) {
        return type == RewardType.STORE_CREDIT ? rupees(value) + " store credit"
                : discount(discountType, value) + " your next membership purchase";
    }

    public static String day(Instant instant, ZoneId zone) {
        return DAY.format(instant.atZone(zone));
    }

    public static String day(LocalDate date) {
        return DAY.format(date);
    }
}
