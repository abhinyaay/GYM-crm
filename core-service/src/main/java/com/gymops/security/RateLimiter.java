package com.gymops.security;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window rate limiter backed by Redis so limits are shared by all application instances.
 * INCR + PEXPIRE run atomically in a Lua script. If Redis is unavailable the limiter fails OPEN:
 * availability of login is preferred, and brute force is still bounded by per-account lockout and
 * the Nginx edge limit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redis;

    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
    }

    public Decision tryAcquire(String key, int limit, Duration window) {
        long windowMs = window.toMillis();
        long bucket = System.currentTimeMillis() / windowMs;
        String redisKey = "gymops:ratelimit:" + key + ":" + bucket;
        try {
            Long count = redis.execute(INCREMENT, List.of(redisKey), String.valueOf(windowMs));
            long current = count == null ? 0 : count;
            long retryAfter = Math.max(1, ((bucket + 1) * windowMs - System.currentTimeMillis()) / 1000);
            return new Decision(current <= limit, Math.max(0, limit - current), retryAfter);
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, failing open: {}", e.getMessage());
            return new Decision(true, limit, 0);
        }
    }
}
