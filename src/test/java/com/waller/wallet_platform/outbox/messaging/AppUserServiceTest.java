package com.waller.wallet_platform.outbox.messaging;

import com.waller.wallet_platform.outbox.entity.OutboxEvent;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.outbox.service.OutboxEventServiceImpl;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doNothing;
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
    private OutboxEventServiceImpl outboxEventService;

    @Mock
    private OutboxEvent event;

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
    void makeSomethingUseful() {
        when(mapper.requestToEntity(request)).thenReturn(testUser);
        when(repository.saveAndFlush(testUser)).thenReturn(testUser);
        when(service.createUser(request)).thenReturn(testUserDto);
        doNothing().when(outboxEventService.record("TEST", testUser.getId(), "USER_CREATED", testUserDto));
        AppUserDto result = service.createUser(request);

        assertNotNull(result);

        assertEquals(result.name(), testUserDto.name());
        assertEquals(result.email(), testUserDto.email());
        assertEquals(result.role(), testUserDto.role());

    }
}
