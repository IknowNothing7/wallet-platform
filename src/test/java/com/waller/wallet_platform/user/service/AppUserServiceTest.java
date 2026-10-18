package com.waller.wallet_platform.user.service;

import java.util.List;
import java.util.Optional;

import com.waller.wallet_platform.common.util.PasswordUtils;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.user.dto.AppUserDto;
import com.waller.wallet_platform.user.dto.UserSearchRequest;
import com.waller.wallet_platform.user.dto.UserSortField;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.mapper.AppUserMapper;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bootstrapAdmin_createsAdminWithNormalizedEmailAndHashedPassword() {
        when(repository.findByEmailIgnoreCase("root@email.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Str0ng!Password")).thenReturn("encoded-password-hash");
        when(repository.saveAndFlush(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        AppUserDto adminDto = AppUserDto.builder().id(7L).role(UserRole.ADMIN).build();
        when(mapper.toDto(any(AppUser.class))).thenReturn(adminDto);

        assertThat(service.bootstrapAdmin("Root", "Root@Email.com", "Str0ng!Password")).isSameAs(adminDto);

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("root@email.com");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("encoded-password-hash");
        assertThat(saved.getValue().getRole()).isEqualTo(UserRole.ADMIN);
        verify(outboxEventService).record("user", 7L, "UserCreated", adminDto);
    }

    @Test
    void bootstrapAdmin_adminAlreadyThere_createsNothing() {
        testUser.setRole(UserRole.ADMIN);
        when(repository.findByEmailIgnoreCase("test@email.com")).thenReturn(Optional.of(testUser));

        assertThat(service.bootstrapAdmin("Root", "test@email.com", "Str0ng!Password")).isNull();

        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxEventService);
    }

    // Whoever registered the email first must not become admin
    @Test
    void bootstrapAdmin_emailTakenByRegularUser_neverPromotesThem() {
        when(repository.findByEmailIgnoreCase("test@email.com")).thenReturn(Optional.of(testUser));

        assertThat(service.bootstrapAdmin("Root", "test@email.com", "Str0ng!Password")).isNull();

        assertThat(testUser.getRole()).isEqualTo(UserRole.USER);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void bootstrapAdmin_weakPassword_failsStartup() {
        when(repository.findByEmailIgnoreCase("root@email.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bootstrapAdmin("Root", "root@email.com", "short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PasswordUtils.INVALID_PASSWORD_MESSAGE);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void changeRole_toMerchant_savesAndRecordsEvent() {
        // The acting admin is logged
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin@email.com", null));
        when(repository.findById(1L)).thenReturn(Optional.of(testUser));
        when(repository.saveAndFlush(testUser)).thenReturn(testUser);
        when(mapper.toDto(testUser)).thenReturn(testUserDto);

        assertThat(service.changeRole(1L, UserRole.MERCHANT)).isSameAs(testUserDto);

        assertThat(testUser.getRole()).isEqualTo(UserRole.MERCHANT);
        verify(outboxEventService).record("user", 1L, "UserRoleChanged", testUserDto);
    }

    @Test
    void changeRole_toAdmin_isRefusedBeforeLookingUpTheUser() {
        assertThatThrownBy(() -> service.changeRole(1L, UserRole.ADMIN))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);

        verifyNoInteractions(repository, outboxEventService);
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
