package com.gymops.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import com.gymops.security.JwtAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One structured access-log line per API request: method, path, status, duration and user id.
 * Query strings and bodies are deliberately not logged (they may contain personal data).
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/actuator") || uri.startsWith("/swagger-ui") || uri.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            Object userId = request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE);
            String user = userId != null ? userId.toString() : "anonymous";
            int status = response.getStatus();
            if (status >= 500) {
                log.error("http_request method={} path={} status={} durationMs={} user={}",
                        request.getMethod(), request.getRequestURI(), status, durationMs, user);
            } else {
                log.info("http_request method={} path={} status={} durationMs={} user={}",
                        request.getMethod(), request.getRequestURI(), status, durationMs, user);
            }
        }
    }
}
