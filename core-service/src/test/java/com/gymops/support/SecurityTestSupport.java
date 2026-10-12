package com.gymops.support;

import com.gymops.security.UserPrincipal;
import com.gymops.user.RoleName;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityTestSupport {

    private SecurityTestSupport() {
    }

    public static UserPrincipal authenticate(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
        return principal;
    }

    public static UserPrincipal receptionist(long... branchIds) {
        Set<Long> ids = new java.util.HashSet<>();
        for (long id : branchIds) {
            ids.add(id);
        }
        return authenticate(new UserPrincipal(100L, "desk@example.com", Set.of(RoleName.RECEPTIONIST), ids, null, null));
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
