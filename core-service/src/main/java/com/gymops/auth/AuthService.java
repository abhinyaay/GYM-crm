package com.gymops.auth;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.auth.dto.AuthDtos.ChangePasswordRequest;
import com.gymops.auth.dto.AuthDtos.LoginRequest;
import com.gymops.auth.dto.AuthDtos.RegisterRequest;
import com.gymops.auth.dto.AuthDtos.TokenResponse;
import com.gymops.auth.dto.AuthDtos.UserInfo;
import com.gymops.branch.StaffAssignmentRepository;
import com.gymops.common.exception.ApiException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.util.Hashing;
import com.gymops.config.AppProperties;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.member.MemberService;
import com.gymops.member.dto.MemberRequest;
import com.gymops.security.CurrentUser;
import com.gymops.security.JwtService;
import com.gymops.security.UserPrincipal;
import com.gymops.trainer.TrainerRepository;
import com.gymops.user.RoleName;
import com.gymops.user.RoleRepository;
import com.gymops.user.User;
import com.gymops.user.UserRepository;
import com.gymops.user.UserStatus;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication: password login with account lockout, short-lived JWT access tokens and rotating
 * opaque refresh tokens with reuse detection.
 */
@Slf4j
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final StaffAssignmentRepository staffAssignmentRepository;
    private final MemberRepository memberRepository;
    private final TrainerRepository trainerRepository;
    private final MemberService memberService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService audit;
    private final AppProperties.Security props;
    private final Clock clock;
    /** Used to spend equal time when the email is unknown, so response timing does not reveal accounts. */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, RoleRepository roleRepository,
                       RefreshTokenRepository refreshTokenRepository, StaffAssignmentRepository staffAssignmentRepository,
                       MemberRepository memberRepository, TrainerRepository trainerRepository, MemberService memberService,
                       PasswordEncoder passwordEncoder, JwtService jwtService, AuditService audit,
                       AppProperties appProperties, Clock clock) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.staffAssignmentRepository = staffAssignmentRepository;
        this.memberRepository = memberRepository;
        this.trainerRepository = trainerRepository;
        this.memberService = memberService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
        this.props = appProperties.security();
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public record ClientInfo(String ip, String userAgent) {
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse login(LoginRequest request, ClientInfo client) {
        Instant now = Instant.now(clock);
        User user = userRepository.findByEmailIgnoreCase(request.email().trim()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }
        user.releaseExpiredLock(now);
        if (user.isLockedAt(now)) {
            throw new ApiException(ErrorCode.ACCOUNT_LOCKED, "Account temporarily locked after repeated failed logins");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.recordFailedLogin(props.maxFailedLogins(), props.lockoutDuration(), now);
            log.warn("security_event=login_failed userId={} ip={} locked={}", user.getId(), client.ip(),
                    user.getStatus() == UserStatus.LOCKED);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED, "Account is disabled");
        }
        user.recordSuccessfulLogin(now);
        log.info("security_event=login_succeeded userId={} ip={}", user.getId(), client.ip());
        return issueTokens(user, UUID.randomUUID(), client, now);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(String rawRefreshToken, ClientInfo client) {
        Instant now = Instant.now(clock);
        RefreshToken token = refreshTokenRepository.findByHashForUpdate(Hashing.sha256Hex(rawRefreshToken))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_TOKEN, "Invalid refresh token"));
        if (token.getRevokedAt() != null) {
            // A rotated/revoked token was presented again: assume theft, kill the whole session family.
            int revoked = refreshTokenRepository.revokeFamily(token.getFamilyId(), now, "REUSE_DETECTED");
            log.warn("security_event=refresh_token_reuse userId={} familyId={} revoked={} ip={}",
                    token.getUser().getId(), token.getFamilyId(), revoked, client.ip());
            throw new ApiException(ErrorCode.INVALID_TOKEN, "Refresh token has been revoked");
        }
        if (!token.isActiveAt(now)) {
            throw new ApiException(ErrorCode.INVALID_TOKEN, "Refresh token has expired");
        }
        User user = token.getUser();
        if (user.getStatus() != UserStatus.ACTIVE || user.getDeletedAt() != null) {
            refreshTokenRepository.revokeFamily(token.getFamilyId(), now, "ACCOUNT_INACTIVE");
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED, "Account is not active");
        }
        TokenResponse response = issueTokens(user, token.getFamilyId(), client, now);
        RefreshToken replacement = refreshTokenRepository
                .findByHashForUpdate(Hashing.sha256Hex(response.refreshToken())).orElseThrow();
        token.revoke(now, "ROTATED", replacement.getId());
        return response;
    }

    /** Revokes the session (refresh-token family) the given token belongs to. Idempotent. */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByHashForUpdate(Hashing.sha256Hex(rawRefreshToken))
                .ifPresent(t -> refreshTokenRepository.revokeFamily(t.getFamilyId(), Instant.now(clock), "LOGOUT"));
    }

    @Transactional
    public void logoutEverywhere() {
        refreshTokenRepository.revokeAllForUser(CurrentUser.get().userId(), Instant.now(clock), "LOGOUT_ALL");
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        UserPrincipal caller = CurrentUser.get();
        User user = userRepository.findById(caller.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "User no longer exists"));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Current password is incorrect");
        }
        Instant now = Instant.now(clock);
        user.changePassword(passwordEncoder.encode(request.newPassword()), now);
        refreshTokenRepository.revokeAllForUser(user.getId(), now, "PASSWORD_CHANGED");
        audit.record(AuditEvent.of("USER", user.getId(), "PASSWORD_CHANGED"));
    }

    /** Self-service member sign-up: creates a MEMBER login and the linked member profile atomically. */
    @Transactional
    public TokenResponse register(RegisterRequest request, ClientInfo client) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("An account with this email already exists");
        }
        Instant now = Instant.now(clock);
        User user = userRepository.save(new User(request.email(), passwordEncoder.encode(request.password()),
                request.firstName() + " " + request.lastName(), request.phone(),
                Set.of(roleRepository.findByName(RoleName.MEMBER).orElseThrow()), now));
        memberService.registerForUser(new MemberRequest(request.homeBranchId(), request.firstName(), request.lastName(),
                request.email(), request.phone(), request.dateOfBirth(), request.gender(), null, null, null, null,
                null, List.of()), user);
        audit.record(AuditEvent.of("USER", user.getId(), "SELF_REGISTERED"));
        user.recordSuccessfulLogin(now);
        return issueTokens(user, UUID.randomUUID(), client, now);
    }

    /** Builds the authorization snapshot embedded in the access token. */
    public UserPrincipal principalFor(User user) {
        Set<Long> branches = user.roleNames().stream().anyMatch(RoleName.BRANCH_SCOPED_STAFF::contains)
                ? staffAssignmentRepository.findBranchIdsByUserId(user.getId()) : Set.of();
        Long memberId = user.hasRole(RoleName.MEMBER)
                ? memberRepository.findByUserId(user.getId()).map(Member::getId).orElse(null) : null;
        Long trainerId = user.hasRole(RoleName.TRAINER) ? trainerRepository.findIdByUserId(user.getId()).orElse(null) : null;
        return new UserPrincipal(user.getId(), user.getEmail(), user.roleNames(), branches, memberId, trainerId);
    }

    private TokenResponse issueTokens(User user, UUID familyId, ClientInfo client, Instant now) {
        UserPrincipal principal = principalFor(user);
        JwtService.IssuedToken access = jwtService.issueAccessToken(principal);
        String rawRefresh = newOpaqueToken();
        Instant refreshExpiry = now.plus(props.jwt().refreshTokenTtl());
        refreshTokenRepository.saveAndFlush(new RefreshToken(user, Hashing.sha256Hex(rawRefresh), familyId,
                refreshExpiry, now, client.ip(), client.userAgent()));
        return new TokenResponse("Bearer", access.token(), access.expiresAt(), rawRefresh, refreshExpiry,
                new UserInfo(user.getId(), user.getEmail(), user.getFullName(), principal.roles(),
                        principal.branchIds(), principal.memberId(), principal.trainerId()));
    }

    private static String newOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
