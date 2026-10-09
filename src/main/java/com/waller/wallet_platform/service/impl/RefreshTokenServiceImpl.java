package com.waller.wallet_platform.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.model.entites.AppUser;
import com.waller.wallet_platform.model.entites.RefreshToken;
import com.waller.wallet_platform.repositories.RefreshTokenRepository;
import com.waller.wallet_platform.service.RefreshTokenService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private static final int TOKEN_BYTES = 32;
    private static final String INVALID_TOKEN = "Invalid refresh token";

    private final SecureRandom secureRandom = new SecureRandom();
    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.refresh-lifetime:P7D}")
    private Duration refreshLifetime;

    @Override
    @Transactional
    public String issue(AppUser user) {
        String rawToken = generateRawToken();
        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(hash(rawToken))
                .expiresAt(Instant.now().plus(refreshLifetime))
                .build());
        return rawToken;
    }

    // Reuse detection revokes all of the user's tokens and then throws; that revocation must not be rolled back
    @Override
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Rotation rotate(String rawToken) {
        RefreshToken token = findByRawToken(rawToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_TOKEN));
        AppUser user = token.getUser();

        if (token.isRevoked()) {
            reuseDetected(user);
        }
        if (token.isExpired()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_TOKEN);
        }
        // Lost a race with a concurrent rotation of the same token
        if (refreshTokenRepository.revokeIfActive(token.getId(), Instant.now()) == 0) {
            reuseDetected(user);
        }

        return new Rotation(user, issue(user));
    }

    // Logout deletes instead of revoking: a later refresh with this token is then simply unknown (401),
    // rather than looking like reuse of a rotated token and revoking every session the user has
    @Override
    @Transactional
    public void revoke(String rawToken) {
        findByRawToken(rawToken)
                .ifPresent(token -> refreshTokenRepository.deleteIfActive(token.getId()));
    }

    @Override
    @Transactional
    public void revokeAll(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
    }

    @Scheduled(cron = "${jwt.refresh-cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void purgeExpired() {
        int deleted = refreshTokenRepository.deleteExpiredBefore(Instant.now());
        log.info("Purged {} expired refresh tokens", deleted);
    }

    private void reuseDetected(AppUser user) {
        log.warn("Refresh token reuse detected for user {}, revoking all sessions", user.getId());
        revokeAll(user.getId());
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_TOKEN);
    }

    private Optional<RefreshToken> findByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return refreshTokenRepository.findByTokenHashWithUser(hash(rawToken));
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

}
