package com.gymops.security;

import com.gymops.common.exception.ForbiddenException;
import com.gymops.user.RoleName;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Data-level (row-level) authorization. Role checks ({@code @PreAuthorize}) answer "may this role
 * call this endpoint"; this policy answers "may this caller touch THIS branch / THIS member".
 *
 * <ul>
 *   <li>SUPER_ADMIN, GYM_ADMIN: every branch</li>
 *   <li>MANAGER, RECEPTIONIST, TRAINER: only branches they are assigned to</li>
 *   <li>MEMBER: only their own member record</li>
 * </ul>
 */
@Component("access")
public class AccessPolicy {

    public boolean canAccessBranch(Long branchId) {
        UserPrincipal p = CurrentUser.get();
        return p.isOrgWide() || (p.hasAnyRole(RoleName.BRANCH_SCOPED_STAFF) && p.branchIds().contains(branchId));
    }

    public void assertBranchAccess(Long branchId) {
        if (!canAccessBranch(branchId)) {
            throw new ForbiddenException("You are not assigned to branch " + branchId);
        }
    }

    /**
     * Staff of the member's home branch (or org-wide staff) may access a member; a MEMBER may only
     * access their own record.
     */
    public void assertMemberAccess(Long memberId, Long memberHomeBranchId) {
        UserPrincipal p = CurrentUser.get();
        if (p.isOrgWide()) {
            return;
        }
        if (p.hasAnyRole(RoleName.BRANCH_SCOPED_STAFF) && p.branchIds().contains(memberHomeBranchId)) {
            return;
        }
        if (p.hasRole(RoleName.MEMBER) && Objects.equals(p.memberId(), memberId)) {
            return;
        }
        throw new ForbiddenException("You may not access member " + memberId);
    }

    /** True when the caller is the member themselves (used to hide staff-only data). */
    public boolean isSelf(Long memberId) {
        return CurrentUser.find().map(p -> Objects.equals(p.memberId(), memberId)).orElse(false);
    }

    /**
     * Branch filter to apply on list queries: {@code Optional.empty()} means unrestricted.
     * If a specific branch is requested it is validated against the caller's scope.
     */
    public Optional<Set<Long>> branchScope(Long requestedBranchId) {
        UserPrincipal p = CurrentUser.get();
        if (requestedBranchId != null) {
            assertBranchAccess(requestedBranchId);
            return Optional.of(Set.of(requestedBranchId));
        }
        if (p.isOrgWide()) {
            return Optional.empty();
        }
        return Optional.of(p.branchIds());
    }

    /** Branch scope as a query-friendly value (avoids nullable collection parameters in JPQL). */
    public BranchScope scope(Long requestedBranchId) {
        return branchScope(requestedBranchId).map(BranchScope::of).orElse(BranchScope.ALL);
    }

    public static Collection<Long> orNoMatch(Set<Long> ids) {
        return ids.isEmpty() ? Set.of(-1L) : ids;
    }
}
