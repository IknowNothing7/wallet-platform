package com.waller.wallet_platform.user.service;

import java.util.List;

import com.waller.wallet_platform.common.util.PasswordUtils;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.user.dto.AppUserDto;
import com.waller.wallet_platform.user.dto.AppUserRequest;
import com.waller.wallet_platform.user.dto.UserSearchRequest;
import com.waller.wallet_platform.user.dto.UserSortField;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.mapper.AppUserMapper;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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

    @Test
    void concurrentDuplicateEmailIsConflict() {
        // Another request saved the same email between the exists check and the insert
        AppUser mapped = new AppUser();
        when(mapper.requestToEntity(request)).thenReturn(mapped);
        when(passwordEncoder.encode(request.getPassword())).thenReturn("encoded-password-hash");
        when(repository.saveAndFlush(mapped)).thenThrow(new DataIntegrityViolationException("uq_app_user_email"));

        assertThatThrownBy(() -> service.createUser(request)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Email is already registered")
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);

        verifyNoInteractions(outboxEventService);
    }

    @Test
    void getUsersReturnsRequestedPageSortedById() {
        PageRequest pageable = PageRequest.of(1, 2, Sort.by("id"));
        when(repository.findAll(ArgumentMatchers.<Specification<AppUser>>any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(testUser), pageable, 3));
        when(mapper.toDto(testUser)).thenReturn(testUserDto);

        PageResponse<AppUserDto> result = service.getUsers(new UserSearchRequest(), 1, 2);

        assertThat(result.content()).containsExactly(testUserDto);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(2);
    }

    // The chosen field first, then id, so ties keep a stable order across pages
    @Test
    void getUsersSortsByChosenFieldThenId() {
        when(repository.findAll(ArgumentMatchers.<Specification<AppUser>>any(), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(1), 0));

        service.getUsers(UserSearchRequest.builder().sortField(UserSortField.NAME).build(), 0, 20);
        service.getUsers(UserSearchRequest.builder().sortField(UserSortField.EMAIL).build(), 0, 20);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(2)).findAll(ArgumentMatchers.<Specification<AppUser>>any(), pageable.capture());
        assertThat(pageable.getAllValues()).extracting(Pageable::getSort)
                .containsExactly(Sort.by("name", "id"), Sort.by("email", "id"));
    }

}
