package com.waller.wallet_platform.account.service;

import com.waller.wallet_platform.account.dto.AccountDto;
import com.waller.wallet_platform.account.dto.AccountRequest;
import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.AccountStatus;
import com.waller.wallet_platform.account.mapper.AccountMapper;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.security.AccountAccessChecker;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final String EMAIL = "test@example.com";

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private AccountMapper accountMapper;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private OutboxEventService outboxEventService;
    @Mock
    private AccountAccessChecker accessChecker;

    @InjectMocks
    private AccountServiceImpl service;

    private Account testAccount;
    private AccountDto accountDto;
    private AppUser user;
    private AccountRequest accountRequest;

    @BeforeEach
    void setUp() {
        user = new AppUser();
        user.setName("TestUser");
        user.setEmail(EMAIL);
        user.setPasswordHash("TestPassword");
        user.setRole(UserRole.USER);
        user.setId(112L);

        testAccount = new Account();
        testAccount.setId(1L);
        testAccount.setUser(user);
        testAccount.setBalance(100L);
        testAccount.setCurrency("USD");
        testAccount.setStatus(AccountStatus.ACTIVE);

        accountDto = AccountDto.builder()
                .userId(user.getId())
                .status(AccountStatus.ACTIVE)
                .id(1L)
                .currency("USD")
                .balance(100L)
                .build();

        accountRequest = new AccountRequest();
        accountRequest.setCurrency("USD");

        // The service takes the owner from the principal's name, which is the user's email
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(EMAIL, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getIdByAccountExistReturnAccountDto() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(testAccount));
        when(accessChecker.canAccess(testAccount)).thenReturn(true);
        when(accountMapper.toAccountDto(testAccount)).thenReturn(accountDto);

        AccountDto result = service.getAccount(1L);

        assertNotNull(result);
        assertEquals(accountDto.id(), result.id());
        assertEquals(accountDto.status(), result.status());
        assertEquals(accountDto.userId(), result.userId());
        assertEquals(accountDto.currency(), result.currency());
        assertEquals(accountDto.balance(), result.balance());

        verify(accountRepository, times(1)).findById(1L);
        verify(accountMapper, times(1)).toAccountDto(testAccount);
    }

    @Test
    void getByIdAndAccountNotFoundThrowsException() {
        when(accountRepository.findById(1000L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccount(1000L)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Account not found");

        verify(accountRepository, times(1)).findById(1000L);
        verifyNoInteractions(accessChecker, accountMapper);
    }

    @Test
    void getAccountButAccessCheckerReturnedFalse() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(testAccount));
        when(accessChecker.canAccess(testAccount)).thenReturn(false);

        // Someone else's account answers exactly like a missing one
        assertThatThrownBy(() -> service.getAccount(1L)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Account not found")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(accountRepository, times(1)).findById(1L);
        verifyNoInteractions(accountMapper);
    }

    @Test
    void createAnAccountAssignsCurrentUserAndRecordsOutboxEvent() {
        Account mapped = new Account();
        mapped.setCurrency("USD");
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(accountMapper.createAnAccount(accountRequest)).thenReturn(mapped);
        when(accountRepository.save(mapped)).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        when(accountMapper.toAccountDto(mapped)).thenReturn(accountDto);

        AccountDto result = service.createAnAccount(accountRequest);

        assertSame(accountDto, result);
        assertSame(user, mapped.getUser());
        verify(accountRepository).save(mapped);
        verify(outboxEventService).record("account", 1L, "AccountCreated", accountDto);
    }

    @Test
    void createAnAccountWithUnknownPrincipalThrowsUnauthorized() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createAnAccount(accountRequest))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(accountRepository, never()).save(any());
        verify(outboxEventService, never()).record(anyString(), anyLong(), anyString(), any());
        verifyNoInteractions(accountMapper);
    }

    @Test
    void getMyAccountsReturnsMappedAccountsOfCurrentUser() {
        Account second = new Account();
        second.setId(2L);
        second.setUser(user);
        second.setCurrency("EUR");
        AccountDto secondDto = AccountDto.builder()
                .id(2L)
                .userId(user.getId())
                .currency("EUR")
                .build();
        when(accountRepository.findByUserEmailIgnoreCaseOrderByIdAsc(EMAIL)).thenReturn(List.of(testAccount, second));
        when(accountMapper.toAccountDto(testAccount)).thenReturn(accountDto);
        when(accountMapper.toAccountDto(second)).thenReturn(secondDto);

        List<AccountDto> result = service.getMyAccounts();

        assertThat(result).containsExactly(accountDto, secondDto);
        verify(accountRepository).findByUserEmailIgnoreCaseOrderByIdAsc(EMAIL);
    }

    @Test
    void getMyAccountsWithNoAccountsReturnsEmptyList() {
        when(accountRepository.findByUserEmailIgnoreCaseOrderByIdAsc(EMAIL)).thenReturn(List.of());

        assertThat(service.getMyAccounts()).isEmpty();
        verifyNoInteractions(accountMapper);
    }
}
