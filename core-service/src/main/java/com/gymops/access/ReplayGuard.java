package com.gymops.access;

import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-use tokens (device nonces, QR pass windows) in PostgreSQL rather than Redis: replay protection is a
 * security control and must not fail open when the cache is down. {@code INSERT ... ON CONFLICT DO NOTHING}
 * never aborts the surrounding transaction; the primary key makes it race-free across instances.
 */
@Component
@RequiredArgsConstructor
public class ReplayGuard {

    private final JdbcTemplate jdbc;

    /** Records the token; false when it was already used (a replay). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(String scope, String token, Instant expiresAt) {
        int inserted = jdbc.update("""
                insert into access_replay_guard (scope, token, expires_at) values (?, ?, ?)
                on conflict (scope, token) do nothing
                """, scope, token, Timestamp.from(expiresAt));
        return inserted == 1;
    }

    @Transactional
    public int purgeExpired(Instant now) {
        return jdbc.update("delete from access_replay_guard where expires_at < ?", Timestamp.from(now));
    }
}
