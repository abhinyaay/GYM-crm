package com.gymops.settings;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * When the owner switches gate access control off, its staff / member API answers 403 FEATURE_DISABLED. The
 * device API ({@code /api/v1/access/gate/**}) is checked by the gate authenticator itself.
 */
@Configuration
@RequiredArgsConstructor
public class FeatureWebConfig implements WebMvcConfigurer {

    /** Lazy: web-layer test slices load this configurer without the service. */
    private final ObjectProvider<FeatureService> features;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                FeatureService service = features.getIfAvailable();
                if (service != null) {
                    service.requireGates();
                }
                return true;
            }
        }).addPathPatterns("/api/v1/access/**").excludePathPatterns("/api/v1/access/gate/**");
    }
}
