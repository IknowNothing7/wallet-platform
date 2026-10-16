package com.waller.wallet_platform.auth;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.bouncycastle.openssl.PasswordException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.github.dockerjava.api.model.AuthResponse;
import com.waller.wallet_platform.auth.dto.LoginRequest;
import com.waller.wallet_platform.auth.service.AuthServiceImpl;
import com.waller.wallet_platform.auth.service.RefreshTokenService;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.security.AccessTokenDenylist;
import com.waller.wallet_platform.security.JwtTokenProvider;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.mapper.AppUserMapper;
import com.waller.wallet_platform.user.repository.AppUserRepository;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class AuthServiceTest {

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

    @BeforeEach
    void setUp() {
        loginRequest = LoginRequest.builder()
                .email("logintest@test.com")
                .password("ItIsATestPassword!1")
                .build();
        ReflectionTestUtils.setField(authServiceImpl, "maxFailedLogins", 5);
        ReflectionTestUtils.setField(authServiceImpl, "failedLoginWindow", Duration.ofMinutes(15));
        ReflectionTestUtils.setField(authServiceImpl, "accessTokenLifetimeMillis", 900_000L);
        ReflectionTestUtils.invokeMethod(authServiceImpl, "init");

        user = new AppUser();
        user.setId(1L);
        user.setRole(UserRole.USER);
        user.setName("TestUser");
        user.setPasswordHash("ItIsATestPassword!1");
        user.setEmail("logintest@test.com");
    }

    @Test
    void tryLoginAndGetSuccess() {
        when(appUserRepository.findByEmailIgnoreCase(loginRequest.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(loginRequest.getPassword(), user.getPasswordHash())).thenReturn(true);
        var response = authServiceImpl.login(loginRequest);

        assertNotNull(response);

    }
}
