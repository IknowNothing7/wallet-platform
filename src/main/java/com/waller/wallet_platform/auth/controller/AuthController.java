package com.waller.wallet_platform.auth.controller;

import com.waller.wallet_platform.common.util.ApiUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.waller.wallet_platform.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.waller.wallet_platform.auth.dto.AuthResponse;
import com.waller.wallet_platform.auth.dto.LoginRequest;
import com.waller.wallet_platform.auth.dto.RefreshTokenRequest;
import com.waller.wallet_platform.auth.dto.RegistrationRequest;
import com.waller.wallet_platform.auth.service.AuthService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegistrationRequest request) {
        log.info("Endpoint {}", ApiUtils.getMethodName());
        return authService.registerUser(request);
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refreshAccessToken(request.getRefreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String accessToken = authorization != null && authorization.startsWith(BEARER_PREFIX)
                ? authorization.substring(BEARER_PREFIX.length())
                : null;
        authService.logout(request.getRefreshToken(), accessToken);
    }

}
