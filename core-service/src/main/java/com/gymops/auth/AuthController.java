package com.gymops.auth;

import com.gymops.auth.dto.AuthDtos.ChangePasswordRequest;
import com.gymops.auth.dto.AuthDtos.LoginRequest;
import com.gymops.auth.dto.AuthDtos.RefreshRequest;
import com.gymops.auth.dto.AuthDtos.RegisterRequest;
import com.gymops.auth.dto.AuthDtos.TokenResponse;
import com.gymops.common.web.ClientIp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Log in with email + password; returns access + refresh tokens",
            security = {})
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request, client(http));
    }

    @Operation(summary = "Self-service member sign-up", security = {})
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        return authService.register(request, client(http));
    }

    @Operation(summary = "Rotate the refresh token and obtain a new access token", security = {})
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        return authService.refresh(request.refreshToken(), client(http));
    }

    @Operation(summary = "Revoke the session of the given refresh token", security = {})
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    @Operation(summary = "Revoke all sessions of the current user")
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll() {
        authService.logoutEverywhere();
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
    }

    private static AuthService.ClientInfo client(HttpServletRequest http) {
        return new AuthService.ClientInfo(ClientIp.of(http), http.getHeader(HttpHeaders.USER_AGENT));
    }
}
