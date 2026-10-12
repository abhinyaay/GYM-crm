package com.gymops.user.dto;

import com.gymops.common.validation.StrongPassword;
import com.gymops.user.RoleName;
import com.gymops.user.User;
import com.gymops.user.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;

public final class UserDtos {

    private UserDtos() {
    }

    /** Creates a staff account (members sign up via /auth/register or are registered at the desk). */
    public record CreateStaffRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @StrongPassword String initialPassword,
            @NotBlank @Size(max = 120) String fullName,
            @Pattern(regexp = "^\\+?[0-9 -]{7,20}$", message = "invalid phone number") String phone,
            @NotEmpty Set<RoleName> roles,
            Set<Long> branchIds) {
    }

    public record UpdateProfileRequest(@NotBlank @Size(max = 120) String fullName,
                                       @Pattern(regexp = "^\\+?[0-9 -]{7,20}$", message = "invalid phone number") String phone) {
    }

    public record ChangeStatusRequest(@NotNull UserStatus status) {
    }

    public record ChangeRolesRequest(@NotEmpty Set<RoleName> roles) {
    }

    public record UserResponse(Long id, String email, String fullName, String phone, UserStatus status,
                               Set<RoleName> roles, Set<Long> branchIds, Instant lastLoginAt, Instant createdAt) {

        public static UserResponse from(User u, Set<Long> branchIds) {
            return new UserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getPhone(), u.getStatus(),
                    u.roleNames(), branchIds, u.getLastLoginAt(), u.getCreatedAt());
        }
    }
}
