package com.waller.wallet_platform.auth;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.auth.dto.AuthResponse;
import com.waller.wallet_platform.auth.dto.LoginRequest;
import com.waller.wallet_platform.auth.dto.RegistrationRequest;
import com.waller.wallet_platform.auth.service.AuthServiceImpl;
import com.waller.wallet_platform.auth.service.RefreshTokenService;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.security.AccessTokenDenylist;
import com.waller.wallet_platform.security.JwtTokenProvider;
import com.waller.wallet_platform.user.dto.AppUserDto;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.mapper.AppUserMapper;
import com.waller.wallet_platform.user.repository.AppUserRepository;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class AuthServiceTest {

    private static final String EMAIL = "logintest@test.com";
    private static final String PASSWORD = "ItIsATestPassword!1";
    private static final String PASSWORD_HASH = "encoded-password-hash";

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private AppUserMapper appUserMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private AccessTokenDenylist accessTokenDenylist;

    @InjectMocks
    private AuthServiceImpl authServiceImpl;

    private LoginRequest loginRequest;

    private AppUser user;

    private AppUserDto appUserDto;

    private RegistrationRequest registrationRequest;

    @BeforeEach
    void setUp() {
        loginRequest = LoginRequest.builder()
                .email(EMAIL)
                .password(PASSWORD)
                .build();
        ReflectionTestUtils.setField(authServiceImpl, "maxFailedLogins", 5);
        ReflectionTestUtils.setField(authServiceImpl, "failedLoginWindow", Duration.ofMinutes(15));
        ReflectionTestUtils.setField(authServiceImpl, "accessTokenLifetimeMillis", 900_000L);
        ReflectionTestUtils.invokeMethod(authServiceImpl, "init");

        user = new AppUser();
        user.setId(1L);
        user.setRole(UserRole.USER);
        user.setName("TestUser");
        user.setPasswordHash(PASSWORD_HASH);
        user.setEmail(EMAIL);

        appUserDto = AppUserDto.builder()
                .id(1L)
                .email(EMAIL)
                .build();

        // Mixed case so the service's email normalization is really tested
        registrationRequest = new RegistrationRequest();
        registrationRequest.setEmail("LoginTest@Test.com");
        registrationRequest.setName("TestUser");
        registrationRequest.setPassword(PASSWORD);
        registrationRequest.setConfirmPassword(PASSWORD);
    }

    @Test
    void tryLoginAndGetSuccess() {
        when(appUserRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
        when(appUserMapper.toDto(user)).thenReturn(appUserDto);
        when(refreshTokenService.issue(user)).thenReturn("refresh-token");
        when(jwtTokenProvider.generateToken(user)).thenReturn("access-token");

        var response = authServiceImpl.login(loginRequest);

        assertTrue(response.isSuccess());
        AuthResponse payload = response.getPayload();
        assertEquals("access-token", payload.accessToken());
        assertEquals("refresh-token", payload.refreshToken());
        assertEquals("Bearer", payload.tokenType());
        assertEquals(900, payload.expiresIn());
        assertEquals(appUserDto, payload.user());
    }

    @Test
    void whenPasswordIncorrectThenThrowException() {
        when(appUserRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, PASSWORD_HASH)).thenReturn(false);

        assertThatThrownBy(() -> authServiceImpl.login(loginRequest)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid email or password")
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNAUTHORIZED);

        verifyNoInteractions(refreshTokenService, jwtTokenProvider);
    }

    @Test
    void tryRegisterAndGetSuccess() {
        AppUser mapped = new AppUser();
        mapped.setName("TestUser");
        when(appUserMapper.registrationToEntity(registrationRequest)).thenReturn(mapped);
        when(passwordEncoder.encode(PASSWORD)).thenReturn(PASSWORD_HASH);
        when(appUserRepository.saveAndFlush(mapped)).thenReturn(user);
        when(appUserMapper.toDto(user)).thenReturn(appUserDto);
        when(refreshTokenService.issue(user)).thenReturn("refresh-token");
        when(jwtTokenProvider.generateToken(user)).thenReturn("access-token");

        var result = authServiceImpl.registerUser(registrationRequest);

        assertTrue(result.isSuccess());
        assertEquals("access-token", result.getPayload().accessToken());
        assertEquals("refresh-token", result.getPayload().refreshToken());

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).saveAndFlush(saved.capture());
        assertEquals(EMAIL, saved.getValue().getEmail());
        assertEquals(PASSWORD_HASH, saved.getValue().getPasswordHash());
        verify(outboxEventService).record("user", 1L, "UserCreated", appUserDto);
    }

    @Test
    void tryRegisterWithExistEmailAndGetException() {
        when(appUserRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> authServiceImpl.registerUser(registrationRequest))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Email is already registered")
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);

        verify(appUserRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxEventService, refreshTokenService);
    }

    @Test
    void whenConcurrentRegistrationWinsThenThrowConflict() {
        when(appUserMapper.registrationToEntity(registrationRequest)).thenReturn(new AppUser());
        when(appUserRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> authServiceImpl.registerUser(registrationRequest))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Email is already registered")
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);

        verifyNoInteractions(outboxEventService, refreshTokenService);
    }
}
