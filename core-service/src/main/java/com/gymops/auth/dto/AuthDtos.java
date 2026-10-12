package com.gymops.auth.dto;

import com.gymops.common.validation.StrongPassword;
import com.gymops.member.Gender;
import com.gymops.user.RoleName;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank @Size(max = 72) String password) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @StrongPassword String newPassword) {
    }

    /** Self-service member sign-up. */
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @StrongPassword String password,
            @NotBlank @Size(max = 80) String firstName,
            @NotBlank @Size(max = 80) String lastName,
            @NotBlank @Pattern(regexp = "^\\+?[0-9 -]{7,20}$", message = "invalid phone number") String phone,
            @NotNull Long homeBranchId,
            @Past LocalDate dateOfBirth,
            Gender gender) {
    }

    public record TokenResponse(
            String tokenType,
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken,
            Instant refreshTokenExpiresAt,
            UserInfo user) {
    }

    public record UserInfo(Long id, String email, String fullName, Set<RoleName> roles, Set<Long> branchIds,
                           Long memberId, Long trainerId) {
    }
}
