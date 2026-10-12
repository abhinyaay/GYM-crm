package com.gymops.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class QrPassCodecTest {

    private static final Duration WINDOW = Duration.ofSeconds(30);
    private final AccessKeys keys = new AccessKeys("q".repeat(40));

    @Test
    void windowsAreThirtySecondBuckets() {
        long w = QrPassCodec.window(Instant.ofEpochSecond(1_760_000_010L), WINDOW);
        assertThat(w).isEqualTo(1_760_000_010L / 30);
        assertThat(QrPassCodec.window(Instant.ofEpochSecond(w * 30 + 29), WINDOW)).isEqualTo(w);
        assertThat(QrPassCodec.window(Instant.ofEpochSecond(w * 30 + 30), WINDOW)).isEqualTo(w + 1);
        assertThat(QrPassCodec.windowEnd(w, WINDOW)).isEqualTo(Instant.ofEpochSecond((w + 1) * 30));
    }

    @Test
    void tokenRoundTripsAndIsBoundToMemberCredentialAndWindow() {
        String token = QrPassCodec.token(keys, 4242L, 17L, 58_666_666L);
        assertThat(token).startsWith("GQ1.").hasSizeLessThan(60);
        QrPassCodec.Parsed parsed = QrPassCodec.parse(token);
        assertThat(parsed).isNotNull();
        assertThat(parsed.memberId()).isEqualTo(4242L);
        assertThat(parsed.window()).isEqualTo(58_666_666L);
        assertThat(QrPassCodec.macMatches(keys, parsed, 17L)).isTrue();
        // re-issued pass (new credential id) invalidates old tokens
        assertThat(QrPassCodec.macMatches(keys, parsed, 18L)).isFalse();
        // another server key
        assertThat(QrPassCodec.macMatches(new AccessKeys("r".repeat(40)), parsed, 17L)).isFalse();
        // forging another member / window with the same MAC fails
        String[] parts = token.split("\\.");
        String otherMember = parts[0] + "." + Long.toString(4243L, 36) + "." + parts[2] + "." + parts[3];
        assertThat(QrPassCodec.macMatches(keys, QrPassCodec.parse(otherMember), 17L)).isFalse();
        String otherWindow = parts[0] + "." + parts[1] + "." + Long.toString(58_666_667L, 36) + "." + parts[3];
        assertThat(QrPassCodec.macMatches(keys, QrPassCodec.parse(otherWindow), 17L)).isFalse();
    }

    @Test
    void windowToleranceIsPlusMinusOne() {
        assertThat(QrPassCodec.inWindow(100, 100, 1)).isTrue();
        assertThat(QrPassCodec.inWindow(99, 100, 1)).isTrue();
        assertThat(QrPassCodec.inWindow(101, 100, 1)).isTrue();
        assertThat(QrPassCodec.inWindow(98, 100, 1)).isFalse();
        assertThat(QrPassCodec.inWindow(102, 100, 1)).isFalse();
        assertThat(QrPassCodec.inWindow(99, 100, 0)).isFalse();
    }

    @Test
    void malformedTokensAreRejected() {
        assertThat(QrPassCodec.parse(null)).isNull();
        assertThat(QrPassCodec.parse("")).isNull();
        assertThat(QrPassCodec.parse("GQ2.a.b.cccccccccccccccccccccc")).isNull();
        assertThat(QrPassCodec.parse("GQ1.a.b")).isNull();
        assertThat(QrPassCodec.parse("GQ1.!!.b.cccccccccccccccccccccc")).isNull();
        assertThat(QrPassCodec.parse("GQ1.a.b.short")).isNull();
        assertThat(QrPassCodec.parse("GQ1.0.b.cccccccccccccccccccccc")).isNull();
        assertThat(QrPassCodec.parse("GQ1." + "z".repeat(90))).isNull();
    }

    @Test
    void replayKeyIsPerMemberAndWindow() {
        assertThat(QrPassService.claimKey(5, 100)).isEqualTo("5:100");
        assertThat(QrPassService.claimKey(5, 101)).isNotEqualTo(QrPassService.claimKey(5, 100));
    }
}
