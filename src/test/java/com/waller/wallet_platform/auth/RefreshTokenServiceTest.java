package com.waller.wallet_platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.auth.entity.RefreshToken;
import com.waller.wallet_platform.auth.repository.RefreshTokenRepository;
import com.waller.wallet_platform.auth.service.RefreshTokenService.Rotation;
import com.waller.wallet_platform.auth.service.RefreshTokenServiceImpl;
import com.waller.wallet_platform.user.entity.AppUser;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Duration LIFETIME = Duration.ofDays(7);
    private static final Long USER_ID = 42L;
    private static final Long TOKEN_ID = 7L;
    private static final String RAW_TOKEN = "raw-refresh-token";

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private RefreshTokenServiceImpl refreshTokenService;

    private AppUser user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(refreshTokenService, "refreshLifetime", LIFETIME);
        user = AppUser.builder().id(USER_ID).build();
    }

    // ---- issue ----

    @Test
    void issue_storesOnlyHashOfRawTokenWithExpiry() {
        Instant before = Instant.now();

        String raw = refreshTokenService.issue(user);

        Instant after = Instant.now();
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        RefreshToken saved = captor.getValue();

        assertThat(raw).matches("[A-Za-z0-9_-]{43}"); // 32 bytes, URL-safe Base64 without padding
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getTokenHash()).isEqualTo(sha256(raw)).isNotEqualTo(raw);
        assertThat(saved.getExpiresAt()).isBetween(before.plus(LIFETIME), after.plus(LIFETIME));
        assertThat(saved.getRevokedAt()).isNull();
    }

    @Test
    void issue_generatesDistinctTokens() {
        String first = refreshTokenService.issue(user);
        String second = refreshTokenService.issue(user);

        assertThat(first).isNotEqualTo(second);
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).save(captor.capture());
        List<RefreshToken> saved = captor.getAllValues();
        assertThat(saved.get(0).getTokenHash()).isNotEqualTo(saved.get(1).getTokenHash());
    }

    // ---- rotate ----

    @Test
    void rotate_revokesOldTokenAndIssuesNewOne() {
        RefreshToken stored = storedToken(Instant.now().plus(Duration.ofHours(1)), null);
        when(refreshTokenRepository.findByTokenHashWithUser(sha256(RAW_TOKEN))).thenReturn(Optional.of(stored));
        when(refreshTokenRepository.revokeIfActive(eq(TOKEN_ID), any(Instant.class))).thenReturn(1);

        Rotation rotation = refreshTokenService.rotate(RAW_TOKEN);

        assertThat(rotation.user()).isSameAs(user);
        assertThat(rotation.refreshToken()).isNotBlank().isNotEqualTo(RAW_TOKEN);
        verify(refreshTokenRepository).revokeIfActive(eq(TOKEN_ID), any(Instant.class));

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenHash()).isEqualTo(sha256(rotation.refreshToken()));
        assertThat(captor.getValue().getUser()).isSameAs(user);
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong(), any());
    }

    @Test
    void rotate_unknownToken_rejected() {
        when(refreshTokenRepository.findByTokenHashWithUser(sha256(RAW_TOKEN))).thenReturn(Optional.empty());

        assertUnauthorized(() -> refreshTokenService.rotate(RAW_TOKEN));

        verify(refreshTokenRepository, never()).revokeIfActive(anyLong(), any());
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong(), any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rotate_blankToken_rejectedWithoutLookup(String rawToken) {
        assertUnauthorized(() -> refreshTokenService.rotate(rawToken));

        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    void rotate_reuseOfRevokedToken_revokesAllUserSessions() {
        RefreshToken stored = storedToken(Instant.now().plus(Duration.ofHours(1)), Instant.now().minusSeconds(60));
        when(refreshTokenRepository.findByTokenHashWithUser(sha256(RAW_TOKEN))).thenReturn(Optional.of(stored));

        assertUnauthorized(() -> refreshTokenService.rotate(RAW_TOKEN));

        verify(refreshTokenRepository).revokeAllByUserId(eq(USER_ID), any(Instant.class));
        verify(refreshTokenRepository, never()).revokeIfActive(anyLong(), any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void rotate_expiredToken_rejectedWithoutRevokingSessions() {
        RefreshToken stored = storedToken(Instant.now().minusSeconds(1), null);
        when(refreshTokenRepository.findByTokenHashWithUser(sha256(RAW_TOKEN))).thenReturn(Optional.of(stored));

        assertUnauthorized(() -> refreshTokenService.rotate(RAW_TOKEN));

        verify(refreshTokenRepository, never()).revokeIfActive(anyLong(), any());
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong(), any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void rotate_lostConcurrentRotation_treatedAsReuse() {
        RefreshToken stored = storedToken(Instant.now().plus(Duration.ofHours(1)), null);
        when(refreshTokenRepository.findByTokenHashWithUser(sha256(RAW_TOKEN))).thenReturn(Optional.of(stored));
        when(refreshTokenRepository.revokeIfActive(eq(TOKEN_ID), any(Instant.class))).thenReturn(0);

        assertUnauthorized(() -> refreshTokenService.rotate(RAW_TOKEN));

        verify(refreshTokenRepository).revokeAllByUserId(eq(USER_ID), any(Instant.class));
        verify(refreshTokenRepository, never()).save(any());
    }

    // ---- revoke ----

    @Test
    void revoke_knownToken_deletesItWithoutTouchingOtherSessions() {
        RefreshToken stored = storedToken(Instant.now().plus(Duration.ofHours(1)), null);
        when(refreshTokenRepository.findByTokenHashWithUser(sha256(RAW_TOKEN))).thenReturn(Optional.of(stored));

        refreshTokenService.revoke(RAW_TOKEN);

        verify(refreshTokenRepository).deleteIfActive(TOKEN_ID);
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong(), any());
        verify(refreshTokenRepository, never()).revokeIfActive(anyLong(), any());
    }

    @Test
    void revoke_unknownToken_isNoOp() {
        when(refreshTokenRepository.findByTokenHashWithUser(anyString())).thenReturn(Optional.empty());

        refreshTokenService.revoke(RAW_TOKEN);

        verify(refreshTokenRepository, never()).deleteIfActive(anyLong());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void revoke_blankToken_isNoOp(String rawToken) {
        refreshTokenService.revoke(rawToken);

        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    void revokeAll_revokesAllActiveTokensOfUser() {
        Instant before = Instant.now();

        refreshTokenService.revokeAll(USER_ID);

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(refreshTokenRepository).revokeAllByUserId(eq(USER_ID), captor.capture());
        assertThat(captor.getValue()).isBetween(before, Instant.now());
    }

    // ---- cleanup ----

    @Test
    void purgeExpired_deletesTokensExpiredBeforeNow() {
        Instant before = Instant.now();
        when(refreshTokenRepository.deleteExpiredBefore(any(Instant.class))).thenReturn(3);

        refreshTokenService.purgeExpired();

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(refreshTokenRepository).deleteExpiredBefore(captor.capture());
        assertThat(captor.getValue()).isBetween(before, Instant.now());
    }

    // ---- helpers ----

    private RefreshToken storedToken(Instant expiresAt, Instant revokedAt) {
        return RefreshToken.builder()
                .id(TOKEN_ID)
                .user(user)
                .tokenHash(sha256(RAW_TOKEN))
                .expiresAt(expiresAt)
                .revokedAt(revokedAt)
                .build();
    }

    private static void assertUnauthorized(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
