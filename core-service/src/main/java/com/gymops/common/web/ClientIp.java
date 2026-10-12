package com.gymops.common.web;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIp {

    private ClientIp() {
    }

    /**
     * Resolves the caller IP. {@code server.forward-headers-strategy=native} makes Tomcat honour
     * X-Forwarded-For from the trusted proxy (Nginx / ALB), so getRemoteAddr() is already correct.
     */
    public static String of(HttpServletRequest request) {
        return request == null ? null : request.getRemoteAddr();
    }
}
