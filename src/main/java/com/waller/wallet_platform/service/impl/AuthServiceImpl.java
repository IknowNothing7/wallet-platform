package com.waller.wallet_platform.service.impl;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
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
import com.waller.wallet_platform.security.AccessTokenDenylist;
import com.waller.wallet_platform.security.JwtTokenProvider;
import com.waller.wallet_platform.security.RateLimiter;
import com.waller.wallet_platform.service.AuthService;
import com.waller.wallet_platform.service.RefreshTokenService;
import com.waller.wallet_platform.utils.EmailUtils;
import com.waller.wallet_platform.utils.PasswordUtils;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String TOKEN_TYPE = "Bearer";
    private static final String INVALID_CREDENTIALS = "Invalid email or password";
    private static final String EMAIL_TAKEN = "Email is already registered";
    private static final String TOO_MANY_FAILED_LOGINS = "Too many failed login attempts, try again later";

    private final AppUserRepository userRepository;
    private final AppUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;

    private final AccessTokenDenylist accessTokenDenylist;

    @Value("${jwt.lifetime}")
    private long accessTokenLifetimeMillis;

    @Value("${auth.rate-limit.login.max-failures:5}")
    private int maxFailedLogins;

    @Value("${auth.rate-limit.login.window:PT15M}")
    private Duration failedLoginWindow;

    // Failed logins per email, so one account can't be brute-forced from many IPs
    private RateLimiter failedLogins;

    // Hash checked when the email is unknown, so that path takes as long as a wrong password
    private String dummyPasswordHash;

    @PostConstruct
    void init() {
        failedLogins = new RateLimiter(maxFailedLogins, failedLoginWindow);
        dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public ApiResponse<AuthResponse> login(LoginRequest request) {
        String email = EmailUtils.normalize(request.getEmail());
        // Applies to unknown emails too, so it doesn't reveal which accounts exist
        if (failedLogins.isLimited(email)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_FAILED_LOGINS);
        }

        // Same error and same work for unknown email and wrong password so emails can't be enumerated
        Optional<AppUser> found = userRepository.findByEmailIgnoreCase(email);
        String passwordHash = found.map(AppUser::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), passwordHash);
        if (found.isEmpty() || !passwordMatches) {
            failedLogins.record(email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        }
        failedLogins.reset(email);

        AppUser user = found.get();
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, PasswordUtils.INVALID_PASSWORD_MESSAGE);
        }
        String email = EmailUtils.normalize(request.getEmail());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, EMAIL_TAKEN);
        }

        AppUser user = userMapper.registrationToEntity(request);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        AppUser saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // A concurrent registration with the same email won the race past the exists check
            throw new ResponseStatusException(HttpStatus.CONFLICT, EMAIL_TAKEN);
        }
        log.info("User {} registered", saved.getId());

        return ApiResponse.ok(buildResponse(saved, refreshTokenService.issue(saved)));
    }

    @Override
    public void logout(String refreshToken, String accessToken) {
        refreshTokenService.revoke(refreshToken);
        if (accessToken != null && jwtTokenProvider.isTokenValid(accessToken)) {
            accessTokenDenylist.revoke(jwtTokenProvider.getTokenId(accessToken), jwtTokenProvider.getExpiration(accessToken));
        }
    }

    @Scheduled(fixedDelayString = "${auth.rate-limit.cleanup-interval:PT5M}")
    public void evictExpiredFailedLogins() {
        failedLogins.evictExpired();
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
