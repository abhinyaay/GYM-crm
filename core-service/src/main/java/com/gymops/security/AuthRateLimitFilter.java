package com.gymops.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gymops.common.exception.GlobalExceptionHandler;
import com.gymops.common.web.ClientIp;
import com.gymops.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP throttling of unauthenticated credential endpoints (login, register, refresh) to slow
 * credential stuffing and token brute force.
 */
@RequiredArgsConstructor
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final List<String> PROTECTED = List.of(
            "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/refresh", "/api/v1/leads/enquiry", "/api/v1/public/");

    private final RateLimiter rateLimiter;
    private final AppProperties.RateLimit props;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.enabled() || !"POST".equals(request.getMethod()) || !PROTECTED.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = "auth:" + ClientIp.of(request);
        RateLimiter.Decision decision = rateLimiter.tryAcquire(key, props.authRequestsPerMinute(), Duration.ofMinutes(1));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), GlobalExceptionHandler.error(
                    HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts, retry later", request, List.of()));
            return;
        }
        chain.doFilter(request, response);
    }
}
