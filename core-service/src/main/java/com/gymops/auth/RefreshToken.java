package com.gymops.auth;

import com.gymops.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Server-side record of an opaque refresh token. Only the SHA-256 hash is stored. Tokens rotate on
 * every use; all tokens descending from one login share a {@code familyId} so that re-use of an
 * already-rotated token (a sign of theft) revokes the whole family.
 */
@Getter
@Entity
@Table(name = "refresh_tokens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason")
    private String revokedReason;

    @Column(name = "replaced_by_id")
    private Long replacedById;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by_ip")
    private String createdByIp;

    @Column(name = "user_agent")
    private String userAgent;

    public RefreshToken(User user, String tokenHash, UUID familyId, Instant expiresAt, Instant createdAt,
                        String createdByIp, String userAgent) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.createdByIp = createdByIp;
        this.userAgent = userAgent == null ? null : userAgent.substring(0, Math.min(255, userAgent.length()));
    }

    public boolean isActiveAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(Instant now, String reason, Long replacedById) {
        if (revokedAt == null) {
            this.revokedAt = now;
            this.revokedReason = reason;
            this.replacedById = replacedById;
        }
    }
}
