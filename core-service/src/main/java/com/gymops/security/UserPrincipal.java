package com.gymops.security;

import com.gymops.user.RoleName;
import java.util.Collection;
import java.util.Set;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The authenticated caller, reconstructed from verified JWT claims on every request (stateless:
 * no session and no database round-trip per request).
 *
 * @param branchIds branches a branch-scoped staff member is assigned to (empty for org-wide roles)
 * @param memberId  member record linked to a MEMBER login, otherwise null
 * @param trainerId trainer profile linked to a TRAINER login, otherwise null
 */
public record UserPrincipal(
        Long userId,
        String email,
        Set<RoleName> roles,
        Set<Long> branchIds,
        Long memberId,
        Long trainerId) implements AuthenticatedPrincipal {

    public UserPrincipal {
        roles = Set.copyOf(roles);
        branchIds = Set.copyOf(branchIds);
    }

    @Override
    public String getName() {
        return String.valueOf(userId);
    }

    public boolean hasRole(RoleName role) {
        return roles.contains(role);
    }

    public boolean hasAnyRole(Collection<RoleName> candidates) {
        return candidates.stream().anyMatch(roles::contains);
    }

    public boolean isOrgWide() {
        return hasAnyRole(RoleName.ORG_WIDE);
    }

    public boolean isStaff() {
        return isOrgWide() || hasAnyRole(RoleName.BRANCH_SCOPED_STAFF);
    }

    public boolean isMemberOnly() {
        return roles.size() == 1 && roles.contains(RoleName.MEMBER);
    }

    public Collection<GrantedAuthority> authorities() {
        return roles.stream().map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r.authority())).toList();
    }
}
