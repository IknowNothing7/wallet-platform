package com.waller.wallet_platform.user.service;

import com.waller.wallet_platform.common.util.PasswordUtils;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.user.dto.AppUserDto;
import com.waller.wallet_platform.user.dto.AppUserRequest;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.mapper.AppUserMapper;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class AppUserServiceTest {

    @Mock
    private AppUserRepository repository;

    @Mock
    private AppUserMapper mapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private OutboxEventService outboxEventService;

    @InjectMocks
    private AppUserServiceImpl service;

    private AppUser testUser;
    private AppUserDto testUserDto;
    private AppUserRequest request;

    @BeforeEach
    void setUp() {
        testUser = new AppUser();
        testUser.setId(1L);
        testUser.setRole(UserRole.USER);
        testUser.setName("TestUser");
        testUser.setPasswordHash("testPassword!2");
        testUser.setEmail("test@email.com");

        testUserDto = AppUserDto.builder()
                .id(1L)
                .name("TestUser")
                .role(UserRole.USER)
                .email("test@email.com")
                .build();

        // Mixed case so the service's email normalization is really tested
        request = AppUserRequest.builder()
                .name("TestUser")
                .email("Test@Email.com")
                .password("testPassword!2")
                .build();
    }

    @Test
    void createUserTest() {
        AppUser mapped = new AppUser();
        mapped.setName("TestUser");
        when(mapper.requestToEntity(request)).thenReturn(mapped);
        when(passwordEncoder.encode(request.getPassword())).thenReturn("encoded-password-hash");
        when(repository.saveAndFlush(mapped)).thenReturn(testUser);
        when(mapper.toDto(testUser)).thenReturn(testUserDto);

        AppUserDto result = service.createUser(request);

        assertEquals(testUserDto, result);

        // The service, not the mapper, sets these, so check what was actually saved
        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(repository, times(1)).saveAndFlush(saved.capture());
        assertEquals("test@email.com", saved.getValue().getEmail());
        assertEquals("encoded-password-hash", saved.getValue().getPasswordHash());

        verify(outboxEventService).record("user", 1L, "UserCreated", testUserDto);
    }

    @Test
    void ifUserEmailExistThenThrowException() {
        when(repository.existsByEmailIgnoreCase("test@email.com")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(request)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Email is already registered")
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);

        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxEventService);
    }

    @Test
    void ifPasswordIsNotValidThenThrowException() {
        AppUserRequest badRequest = AppUserRequest.builder()
                .name("TestUser")
                .email("test@email.com")
                .password("short")
                .build();

        assertThatThrownBy(() -> service.createUser(badRequest))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(PasswordUtils.INVALID_PASSWORD_MESSAGE);

        verifyNoInteractions(repository, mapper, passwordEncoder, outboxEventService);
    }

    // TODO: Missing: the race where saveAndFlush throws DataIntegrityViolationException should give 409 "Email is
    //  already registered"; and getUsers paging (sorted by id).

}
