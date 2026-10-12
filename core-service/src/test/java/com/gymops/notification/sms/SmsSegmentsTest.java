package com.gymops.notification.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SmsSegmentsTest {

    @Test
    void gsm7UsesOneSegmentUpTo160AndThen153PerSegment() {
        assertThat(SmsSegments.count("")).isZero();
        assertThat(SmsSegments.count("a".repeat(160))).isEqualTo(1);
        assertThat(SmsSegments.count("a".repeat(161))).isEqualTo(2);
        assertThat(SmsSegments.count("a".repeat(306))).isEqualTo(2);
        assertThat(SmsSegments.count("a".repeat(307))).isEqualTo(3);
    }

    @Test
    void extensionCharactersCountTwice() {
        assertThat(SmsSegments.isGsm7("Pay {now} [at] ~desk")).isTrue();
        assertThat(SmsSegments.count("{".repeat(80))).isEqualTo(1);
        assertThat(SmsSegments.count("{".repeat(81))).isEqualTo(2);
    }

    @Test
    void unicodeFallsBackToUcs2() {
        assertThat(SmsSegments.isGsm7("₹ 999 due")).isFalse();   // rupee sign
        assertThat(SmsSegments.count("न".repeat(70))).isEqualTo(1);
        assertThat(SmsSegments.count("न".repeat(71))).isEqualTo(2);
        assertThat(SmsSegments.count("न".repeat(134))).isEqualTo(2);
        assertThat(SmsSegments.count("न".repeat(135))).isEqualTo(3);
    }
}
