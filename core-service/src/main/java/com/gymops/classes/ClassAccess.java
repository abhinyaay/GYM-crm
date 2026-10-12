package com.gymops.classes;

import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.security.AccessPolicy;
import com.gymops.security.BranchScope;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import com.gymops.subscription.Subscription;
import com.gymops.subscription.SubscriptionRepository;
import com.gymops.subscription.SubscriptionStatus;
import com.gymops.user.RoleName;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Row-level rules for group classes, on top of {@link AccessPolicy}:
 * <ul>
 *   <li>managers/admins of the branch manage the timetable;</li>
 *   <li>front desk and managers of the branch book members and mark attendance;</li>
 *   <li>the instructor sees the roster and marks attendance for their own classes;</li>
 *   <li>members see classes at the branches their plan admits them to and manage only their own bookings.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ClassAccess {

    private static final Set<RoleName> MANAGERS = EnumSet.of(RoleName.SUPER_ADMIN, RoleName.GYM_ADMIN, RoleName.MANAGER);
    private static final Set<RoleName> DESK = EnumSet.of(RoleName.SUPER_ADMIN, RoleName.GYM_ADMIN, RoleName.MANAGER,
            RoleName.RECEPTIONIST);
    private static final Set<SubscriptionStatus> CURRENT = EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.GRACE,
            SubscriptionStatus.FROZEN, SubscriptionStatus.SCHEDULED);

    private final AccessPolicy access;
    private final MemberRepository memberRepository;
    private final SubscriptionRepository subscriptionRepository;

    public boolean isInstructor(ClassSession session) {
        UserPrincipal caller = CurrentUser.get();
        return caller.trainerId() != null && Objects.equals(caller.trainerId(), session.getInstructor().getId());
    }

    public boolean canManage(Long branchId) {
        return CurrentUser.get().hasAnyRole(MANAGERS) && access.canAccessBranch(branchId);
    }

    public void assertCanManage(Long branchId) {
        if (!canManage(branchId)) {
            throw new ForbiddenException("You may not manage classes at branch " + branchId);
        }
    }

    public boolean isDeskStaff(Long branchId) {
        return CurrentUser.get().hasAnyRole(DESK) && access.canAccessBranch(branchId);
    }

    public boolean canMarkAttendance(ClassSession session) {
        return isInstructor(session) || isDeskStaff(session.getBranch().getId());
    }

    public boolean canViewRoster(ClassSession session) {
        return isInstructor(session) || (CurrentUser.get().isStaff() && access.canAccessBranch(session.getBranch().getId()));
    }

    /** Members act only for themselves; staff need access to the class's branch. */
    public void assertCanActForMember(Long memberId, ClassSession session) {
        UserPrincipal caller = CurrentUser.get();
        if (caller.isMemberOnly()) {
            if (!Objects.equals(caller.memberId(), memberId)) {
                throw new ForbiddenException("Members can only manage their own class bookings");
            }
            return;
        }
        if (!isDeskStaff(session.getBranch().getId())) {
            throw new ForbiddenException("You may not manage bookings at branch " + session.getBranch().getId());
        }
    }

    /** Branches whose timetable a member may browse: every branch for all-branch plans, else their home/subscription branch. */
    public BranchScope memberVisibleBranches(Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        List<Subscription> subscriptions = subscriptionRepository.findByMemberAndStatuses(memberId, CURRENT);
        if (subscriptions.stream().anyMatch(s -> s.getPlan().isAllBranchAccess())) {
            return BranchScope.ALL;
        }
        Set<Long> ids = new HashSet<>();
        ids.add(member.getHomeBranch().getId());
        subscriptions.forEach(s -> ids.add(s.getBranch().getId()));
        return BranchScope.of(ids);
    }

    /** Staff may look at a member's class data when they could look at the member. */
    public void assertMemberAccess(Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        access.assertMemberAccess(memberId, member.getHomeBranch().getId());
    }
}
