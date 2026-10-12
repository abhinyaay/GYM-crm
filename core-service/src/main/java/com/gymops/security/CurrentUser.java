package com.gymops.security;

import com.gymops.common.exception.ApiException;
import com.gymops.common.exception.ErrorCode;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<UserPrincipal> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
            return Optional.of(p);
        }
        return Optional.empty();
    }

    public static UserPrincipal get() {
        return find().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Authentication required"));
    }

    public static Long idOrNull() {
        return find().map(UserPrincipal::userId).orElse(null);
    }
}
