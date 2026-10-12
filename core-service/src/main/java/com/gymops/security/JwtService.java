package com.gymops.security;

import com.gymops.config.AppProperties;
import com.gymops.user.RoleName;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies short-lived HS256 access tokens. The analytics service verifies the same
 * tokens with the shared secret, so the claim contract here is part of the inter-service API.
 */
@Service
public class JwtService {

    static final String CLAIM_TYPE = "typ";
    static final String TYPE_ACCESS = "access";

    private final AppProperties.Jwt props;
    private final SecretKey key;
    private final Clock clock;

    public JwtService(AppProperties appProperties, Clock clock) {
        this.props = appProperties.security().jwt();
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public IssuedToken issueAccessToken(UserPrincipal principal) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(props.accessTokenTtl());
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(props.issuer())
                .audience().add(props.audience()).and()
                .subject(String.valueOf(principal.userId()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim("email", principal.email())
                .claim("roles", principal.roles().stream().map(Enum::name).sorted().toList())
                .claim("branches", principal.branchIds().stream().sorted().toList())
                .claim("memberId", principal.memberId())
                .claim("trainerId", principal.trainerId())
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * Verifies signature, issuer, audience, expiry (30s skew) and token type.
     *
     * @throws InvalidTokenException if the token is not a valid access token
     */
    public UserPrincipal parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(props.issuer())
                    .requireAudience(props.audience())
                    .clockSkewSeconds(30)
                    .clock(() -> Date.from(Instant.now(clock)))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
                throw new InvalidTokenException("Not an access token");
            }
            return new UserPrincipal(
                    Long.valueOf(claims.getSubject()),
                    claims.get("email", String.class),
                    toRoles(claims.get("roles", List.class)),
                    toLongs(claims.get("branches", List.class)),
                    toLong(claims.get("memberId")),
                    toLong(claims.get("trainerId")));
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException(e.getMessage());
        }
    }

    private static Set<RoleName> toRoles(Collection<?> raw) {
        if (raw == null) {
            return Set.of();
        }
        return raw.stream().map(Object::toString).map(RoleName::valueOf).collect(Collectors.toSet());
    }

    private static Set<Long> toLongs(Collection<?> raw) {
        if (raw == null) {
            return Set.of();
        }
        return raw.stream().map(JwtService::toLong).collect(Collectors.toSet());
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
