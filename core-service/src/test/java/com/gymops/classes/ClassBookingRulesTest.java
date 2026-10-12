package com.gymops.classes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gymops.common.exception.BusinessRuleException;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClassBookingRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static final Duration WINDOW = Duration.ofDays(30);
    private static final Duration BLOCK = Duration.ofDays(7);
    private static final YearMonth OCT = YearMonth.of(2026, 10);

    private static Instant daysAgo(int days) {
        return NOW.minus(Duration.ofDays(days));
    }

    @Test
    void unlimitedPlanAlwaysAllowed() {
        assertThatCode(() -> ClassBookingRules.checkEntitlement(null, 500, OCT)).doesNotThrowAnyException();
        assertThat(ClassBookingRules.remaining(null, 500)).isNull();
    }

    @Test
    void planWithoutClassesIsRejected() {
        assertThatThrownBy(() -> ClassBookingRules.checkEntitlement(0, 0, OCT))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("does not include group classes");
    }

    @Test
    void creditsAreCountedPerMonth() {
        assertThatCode(() -> ClassBookingRules.checkEntitlement(8, 7, OCT)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ClassBookingRules.checkEntitlement(8, 8, OCT))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("All 8 class credits for October 2026");
        assertThat(ClassBookingRules.remaining(8, 3)).isEqualTo(5);
        assertThat(ClassBookingRules.remaining(8, 11)).isZero();
    }

    @Test
    void twoNoShowsDoNotBlock() {
        assertThat(ClassBookingRules.noShowBlockedUntil(List.of(daysAgo(1), daysAgo(2)), NOW, 3, WINDOW, BLOCK)).isEmpty();
    }

    @Test
    void thirdNoShowWithinWindowBlocksForSevenDaysFromThatNoShow() {
        Optional<Instant> blocked = ClassBookingRules.noShowBlockedUntil(List.of(daysAgo(2), daysAgo(20), daysAgo(10)), NOW, 3,
                WINDOW, BLOCK);
        assertThat(blocked).contains(daysAgo(2).plus(BLOCK));
    }

    @Test
    void blockExpiresAfterSevenDays() {
        assertThat(ClassBookingRules.noShowBlockedUntil(List.of(daysAgo(8), daysAgo(9), daysAgo(12)), NOW, 3, WINDOW, BLOCK))
                .isEmpty();
    }

    @Test
    void noShowsSpreadWiderThanTheWindowDoNotBlock() {
        // three no-shows, but never three inside any 30-day window
        assertThat(ClassBookingRules.noShowBlockedUntil(List.of(daysAgo(1), daysAgo(25), daysAgo(36)), NOW, 3, WINDOW, BLOCK))
                .isEmpty();
    }

    @Test
    void blockMessageNamesTheEndOfTheBlock() {
        Optional<Instant> until = Optional.of(Instant.parse("2026-10-20T04:30:00Z"));
        assertThatThrownBy(() -> ClassBookingRules.checkNoShowPenalty(until, 3, WINDOW, ZoneId.of("Asia/Kolkata")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Class booking is blocked until 20 Oct 2026, 10:00 after 3 no-shows within 30 days");
        assertThatCode(() -> ClassBookingRules.checkNoShowPenalty(Optional.empty(), 3, WINDOW, ZoneId.of("UTC")))
                .doesNotThrowAnyException();
    }
}
