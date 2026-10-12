package com.gymops.user;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.auth.RefreshTokenRepository;
import com.gymops.branch.BranchService;
import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import com.gymops.user.dto.UserDtos.CreateStaffRequest;
import com.gymops.user.dto.UserDtos.UpdateProfileRequest;
import com.gymops.user.dto.UserDtos.UserResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff account administration. Privilege rules:
 * SUPER_ADMIN may grant any role; GYM_ADMIN may only manage MANAGER / TRAINER / RECEPTIONIST
 * accounts (no privilege escalation to admin roles).
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final Set<RoleName> GYM_ADMIN_GRANTABLE = EnumSet.of(RoleName.MANAGER, RoleName.TRAINER,
            RoleName.RECEPTIONIST);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final BranchService branchService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Clock clock;

    @Transactional
    public UserResponse createStaff(CreateStaffRequest request) {
        if (request.roles().contains(RoleName.MEMBER)) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Member accounts are created via member registration");
        }
        assertMayGrant(request.roles());
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("A user with email " + request.email() + " already exists");
        }
        User user = userRepository.saveAndFlush(new User(request.email(), passwordEncoder.encode(request.initialPassword()),
                request.fullName(), request.phone(), roleRepository.findByNameIn(request.roles()), Instant.now(clock)));
        if (request.branchIds() != null) {
            request.branchIds().forEach(branchId -> branchService.assignStaff(branchId, user.getId()));
        }
        audit.record(AuditEvent.of("USER", user.getId(), "STAFF_CREATED").with("roles", request.roles().toString()));
        return UserResponse.from(user, branchService.branchIdsForUser(user.getId()));
    }

    @Transactional(readOnly = true)
    public UserResponse me() {
        return get(CurrentUser.get().userId());
    }

    @Transactional
    public UserResponse updateMyProfile(UpdateProfileRequest request) {
        User user = load(CurrentUser.get().userId());
        user.updateProfile(request.fullName(), request.phone());
        return UserResponse.from(user, branchService.branchIdsForUser(user.getId()));
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        User user = load(id);
        return UserResponse.from(user, branchService.branchIdsForUser(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(RoleName role, UserStatus status, String q, Pageable pageable) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return PageResponse.of(userRepository.search(role, status, like, pageable),
                u -> UserResponse.from(u, branchService.branchIdsForUser(u.getId())));
    }

    @Transactional
    public UserResponse changeStatus(Long id, UserStatus status) {
        User user = load(id);
        assertMayManage(user);
        if (Objects.equals(user.getId(), CurrentUser.get().userId())) {
            throw new BusinessRuleException("You cannot change the status of your own account");
        }
        UserStatus previous = user.getStatus();
        user.changeStatus(status);
        if (status != UserStatus.ACTIVE) {
            refreshTokenRepository.revokeAllForUser(id, Instant.now(clock), "ACCOUNT_" + status);
        }
        audit.record(AuditEvent.of("USER", id, "STATUS_CHANGED").with("from", previous).with("to", status));
        return UserResponse.from(user, branchService.branchIdsForUser(id));
    }

    @Transactional
    public UserResponse changeRoles(Long id, Set<RoleName> roles) {
        User user = load(id);
        assertMayManage(user);   // a GYM_ADMIN must not be able to change (e.g. demote) an administrator
        if (Objects.equals(user.getId(), CurrentUser.get().userId())) {
            throw new BusinessRuleException("You cannot change the roles of your own account");
        }
        if (user.hasRole(RoleName.MEMBER) != roles.contains(RoleName.MEMBER)) {
            throw new BusinessRuleException("The MEMBER role cannot be added to or removed from an existing account");
        }
        assertMayGrant(roles);
        Set<RoleName> previous = user.roleNames();
        user.replaceRoles(roleRepository.findByNameIn(roles));
        // force re-login so new tokens carry the new roles
        refreshTokenRepository.revokeAllForUser(id, Instant.now(clock), "ROLES_CHANGED");
        audit.record(AuditEvent.of("USER", id, "ROLES_CHANGED").with("from", previous.toString()).with("to", roles.toString()));
        return UserResponse.from(user, branchService.branchIdsForUser(id));
    }

    @Transactional
    public void delete(Long id) {
        User user = load(id);
        if (Objects.equals(id, CurrentUser.get().userId())) {
            throw new BusinessRuleException("You cannot delete your own account");
        }
        user.softDelete(Instant.now(clock));
        refreshTokenRepository.revokeAllForUser(id, Instant.now(clock), "ACCOUNT_DELETED");
        audit.record(AuditEvent.of("USER", id, "DELETED"));
    }

    private User load(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    private void assertMayGrant(Set<RoleName> roles) {
        UserPrincipal caller = CurrentUser.get();
        if (caller.hasRole(RoleName.SUPER_ADMIN)) {
            return;
        }
        if (!GYM_ADMIN_GRANTABLE.containsAll(roles)) {
            throw new ForbiddenException("Only SUPER_ADMIN may grant " + roles);
        }
    }

    private void assertMayManage(User target) {
        UserPrincipal caller = CurrentUser.get();
        if (!caller.hasRole(RoleName.SUPER_ADMIN)
                && target.roleNames().stream().anyMatch(r -> r == RoleName.SUPER_ADMIN || r == RoleName.GYM_ADMIN)) {
            throw new ForbiddenException("Only SUPER_ADMIN may manage administrator accounts");
        }
    }
}
