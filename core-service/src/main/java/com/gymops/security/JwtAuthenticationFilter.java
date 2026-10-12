package com.gymops.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests carrying a Bearer access token. Invalid tokens are not rejected here:
 * the request continues unauthenticated and the entry point returns a uniform 401 if the endpoint
 * requires authentication.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    /** Read by the access-log filter, which runs outside the security filter chain. */
    public static final String USER_ID_ATTRIBUTE = "gymops.userId";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            try {
                UserPrincipal principal = jwtService.parseAccessToken(header.substring(BEARER.length()).trim());
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
                SecurityContextHolder.getContext().setAuthentication(authentication);
                MDC.put("userId", String.valueOf(principal.userId()));
                request.setAttribute(USER_ID_ATTRIBUTE, principal.userId());
            } catch (JwtService.InvalidTokenException e) {
                log.debug("Rejected bearer token: {}", e.getMessage());
                request.setAttribute(RestAuthenticationEntryPoint.INVALID_TOKEN_ATTRIBUTE, Boolean.TRUE);
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("userId");
        }
    }
}
