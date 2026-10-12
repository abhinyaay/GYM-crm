package com.gymops.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gymops.config.AppProperties;
import com.gymops.user.RoleName;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class JwtServiceTest {

    public static final String SECRET_FOR_TESTS = "unit-test-secret-unit-test-secret-0123456789";
    static final String SECRET = SECRET_FOR_TESTS;

    public static AppProperties props(String secret, String audience) {
        return new AppProperties("Asia/Kolkata", "INR",
                new AppProperties.Security(new AppProperties.Jwt(secret, "gymops-core", audience, Duration.ofMinutes(15),
                        Duration.ofDays(14)), 5, Duration.ofMinutes(15), List.of()),
                new AppProperties.RateLimit(false, 20),
                new AppProperties.Billing(new BigDecimal("0.18"), 7, 7),
                new AppProperties.Attendance(Duration.ofHours(12), Duration.ofMinutes(90), 25),
                new AppProperties.Subscription(7, 1),
                new AppProperties.Complaints(Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(72), Duration.ofHours(168)),
                new AppProperties.Notifications(100, 5),
                new AppProperties.Analytics("http://localhost:1", Duration.ofMillis(100), Duration.ofMillis(100)),
                new AppProperties.Bootstrap(null, null, false, null));
    }

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
    private final JwtService service = new JwtService(props(SECRET, "gymops-api"), clock);
    private final UserPrincipal principal = new UserPrincipal(7L, "mgr@example.com", Set.of(RoleName.MANAGER),
            Set.of(1L, 2L), null, null);

    @Test
    void roundTripPreservesAuthorizationClaims() {
        var issued = service.issueAccessToken(principal);
        assertThat(issued.expiresAt()).isEqualTo(Instant.parse("2026-03-01T10:15:00Z"));
        UserPrincipal parsed = service.parseAccessToken(issued.token());
        assertThat(parsed).isEqualTo(principal);
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        JwtService other = new JwtService(props("another-secret-another-secret-0123456789xx", "gymops-api"), clock);
        String forged = other.issueAccessToken(principal).token();
        assertThatThrownBy(() -> service.parseAccessToken(forged)).isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    void rejectsExpiredToken() {
        String token = service.issueAccessToken(principal).token();
        JwtService later = new JwtService(props(SECRET, "gymops-api"),
                Clock.fixed(Instant.parse("2026-03-01T10:16:00Z"), ZoneOffset.UTC));
        assertThatThrownBy(() -> later.parseAccessToken(token)).isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    void rejectsWrongAudienceAndNonAccessTokens() {
        JwtService otherAudience = new JwtService(props(SECRET, "another-api"), clock);
        String token = otherAudience.issueAccessToken(principal).token();
        assertThatThrownBy(() -> service.parseAccessToken(token)).isInstanceOf(JwtService.InvalidTokenException.class);

        String refreshTyped = Jwts.builder().subject("7").issuer("gymops-core").audience().add("gymops-api").and()
                .claim("typ", "refresh").issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        assertThatThrownBy(() -> service.parseAccessToken(refreshTyped)).isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> service.parseAccessToken("not.a.jwt")).isInstanceOf(JwtService.InvalidTokenException.class);
    }
}
