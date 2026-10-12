package com.gymops.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UserLockoutTest {

    private final Instant now = Instant.parse("2026-03-01T10:00:00Z");

    @Test
    void locksAfterMaxFailedAttemptsAndUnlocksAfterTimeout() {
        User user = new User("a@example.com", "{bcrypt}x", "A", null, Set.of(), now);
        for (int i = 0; i < 4; i++) {
            user.recordFailedLogin(5, Duration.ofMinutes(15), now);
        }
        assertThat(user.isLockedAt(now)).isFalse();
        user.recordFailedLogin(5, Duration.ofMinutes(15), now);
        assertThat(user.getStatus()).isEqualTo(UserStatus.LOCKED);
        assertThat(user.isLockedAt(now.plusSeconds(60))).isTrue();

        Instant later = now.plus(Duration.ofMinutes(16));
        user.releaseExpiredLock(later);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.isLockedAt(later)).isFalse();
    }

    @Test
    void successfulLoginResetsCounter() {
        User user = new User("a@example.com", "{bcrypt}x", "A", null, Set.of(), now);
        user.recordFailedLogin(5, Duration.ofMinutes(15), now);
        user.recordSuccessfulLogin(now);
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLastLoginAt()).isEqualTo(now);
        assertThat(user.getEmail()).isEqualTo("a@example.com");
    }
}
