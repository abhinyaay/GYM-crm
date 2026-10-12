package com.gymops.user;

import com.gymops.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.SQLRestriction;

@Getter
@Entity
@Table(name = "users")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @BatchSize(size = 50)
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    public User(String email, String passwordHash, String fullName, String phone, Set<Role> roles, Instant now) {
        this.email = email.trim().toLowerCase();
        this.passwordHash = passwordHash;
        this.fullName = fullName.trim();
        this.phone = phone;
        this.roles = new HashSet<>(roles);
        this.passwordChangedAt = now;
    }

    public Set<RoleName> roleNames() {
        return roles.stream().map(Role::getName)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(RoleName.class)));
    }

    public boolean hasRole(RoleName role) {
        return roleNames().contains(role);
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Records a failed password attempt; locks the account after {@code maxAttempts}. */
    public void recordFailedLogin(int maxAttempts, Duration lockout, Instant now) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= maxAttempts) {
            status = UserStatus.LOCKED;
            lockedUntil = now.plus(lockout);
            failedLoginAttempts = 0;
        }
    }

    public void recordSuccessfulLogin(Instant now) {
        failedLoginAttempts = 0;
        lockedUntil = null;
        if (status == UserStatus.LOCKED) {
            status = UserStatus.ACTIVE;
        }
        lastLoginAt = now;
    }

    /** A timed lock that has expired is lifted lazily on the next login attempt. */
    public void releaseExpiredLock(Instant now) {
        if (status == UserStatus.LOCKED && !isLockedAt(now)) {
            status = UserStatus.ACTIVE;
            lockedUntil = null;
        }
    }

    public void changePassword(String newHash, Instant now) {
        this.passwordHash = newHash;
        this.passwordChangedAt = now;
    }

    public void changeStatus(UserStatus newStatus) {
        this.status = newStatus;
        if (newStatus == UserStatus.ACTIVE) {
            this.lockedUntil = null;
            this.failedLoginAttempts = 0;
        }
    }

    public void replaceRoles(Set<Role> newRoles) {
        this.roles.clear();
        this.roles.addAll(newRoles);
    }

    public void updateProfile(String fullName, String phone) {
        this.fullName = fullName.trim();
        this.phone = phone;
    }

    public void softDelete(Instant now) {
        this.deletedAt = now;
        this.status = UserStatus.DISABLED;
    }
}
