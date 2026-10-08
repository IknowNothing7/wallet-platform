package com.waller.wallet_platform.service.impl;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.mapper.AppUserMapper;
import com.waller.wallet_platform.model.entites.AppUser;
import com.waller.wallet_platform.model.request.AppUser.LoginRequest;
import com.waller.wallet_platform.model.request.AppUser.RegistrationRequest;
import com.waller.wallet_platform.model.response.ApiResponse;
import com.waller.wallet_platform.model.response.AuthResponse;
import com.waller.wallet_platform.repositories.AppUserRepository;
import com.waller.wallet_platform.security.JwtTokenProvider;
import com.waller.wallet_platform.service.AuthService;
import com.waller.wallet_platform.service.RefreshTokenService;
import com.waller.wallet_platform.utils.PasswordUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String TOKEN_TYPE = "Bearer";
    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final AppUserRepository userRepository;
    private final AppUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;

    @Value("${jwt.lifetime}")
    private long accessTokenLifetimeMillis;

    @Override
    @Transactional
    public ApiResponse<AuthResponse> login(LoginRequest request) {
        // Same error for unknown email and wrong password so emails can't be enumerated
        AppUser user = userRepository.findByEmail(request.getEmail())
                .filter(u -> passwordEncoder.matches(request.getPassword(), u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS));

        log.info("User {} logged in", user.getId());
        return ApiResponse.ok(buildResponse(user, refreshTokenService.issue(user)));
    }

    @Override
    public ApiResponse<AuthResponse> refreshAccessToken(String token) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(token);
        return ApiResponse.ok(buildResponse(rotation.user(), rotation.refreshToken()));
    }

    @Override
    @Transactional
    public ApiResponse<AuthResponse> registerUser(RegistrationRequest request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passwords do not match");
        }
        if (PasswordUtils.isNotValidPassword(request.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be at least 8 characters and contain upper and lower case letters, a digit and a special character");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }

        AppUser user = userMapper.registrationToEntity(request);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        AppUser saved = userRepository.save(user);
        log.info("User {} registered", saved.getId());

        return ApiResponse.ok(buildResponse(saved, refreshTokenService.issue(saved)));
    }

    @Override
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    private AuthResponse buildResponse(AppUser user, String refreshToken) {
        return AuthResponse.builder()
                .accessToken(jwtTokenProvider.generateToken(user))
                .refreshToken(refreshToken)
                .tokenType(TOKEN_TYPE)
                .expiresIn(TimeUnit.MILLISECONDS.toSeconds(accessTokenLifetimeMillis))
                .user(userMapper.toDto(user))
                .build();
    }

}
