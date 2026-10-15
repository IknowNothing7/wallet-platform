package com.waller.wallet_platform.user.service;

import com.waller.wallet_platform.common.util.PasswordUtils;
// TODO: OutboxEvent and AppUserServiceImpl (same package) are unused imports
import com.waller.wallet_platform.outbox.entity.OutboxEvent;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.user.dto.AppUserDto;
import com.waller.wallet_platform.user.dto.AppUserRequest;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.mapper.AppUserMapper;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import com.waller.wallet_platform.user.service.AppUserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        request = AppUserRequest.builder()
                .name("TestUser")
                .email("test@email.com")
                .password("testPassword!2")
                .build();
    }

    @Test
    void createUserTest() {
        when(mapper.requestToEntity(request)).thenReturn(testUser);
        when(mapper.toDto(testUser)).thenReturn(testUserDto);
        when(passwordEncoder.encode(request.getPassword())).thenReturn((testUser.getPasswordHash()));
        when(repository.saveAndFlush(testUser)).thenReturn(testUser);
        AppUserDto result = service.createUser(request);

        assertNotNull(result);

        // TODO: These compare the mapper stub with itself. Check what the service actually does instead: capture the
        //  saved user and assert passwordHash == encoder output (not the raw password) and that the email is normalized.
        //  Use a mixed-case email in the request so normalization is really tested.
        assertEquals(result.name(), testUserDto.name());
        assertEquals(result.email(), testUserDto.email());
        assertEquals(result.role(), testUserDto.role());

        verify(outboxEventService).record("user", 1L, "UserCreated", testUserDto);
        verify(mapper, times(1)).requestToEntity(request);
        verify(mapper, times(1)).toDto(testUser);
        verify(passwordEncoder, times(1)).encode((request.getPassword()));
        verify(repository, times(1)).saveAndFlush(testUser);
    }

    @Test
    void ifUserEmailExistThenThrowException() {
        when(repository.existsByEmailIgnoreCase(request.getEmail())).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(request)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Email is already registered");

        verify(repository, times(1)).existsByEmailIgnoreCase(request.getEmail());
        // TODO: Assert the 409 status, and verify nothing was saved and no event was recorded.
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
