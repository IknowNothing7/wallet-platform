package com.waller.wallet_platform.account.service;

import com.waller.wallet_platform.account.dto.AccountDto;
import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.AccountStatus;
import com.waller.wallet_platform.account.mapper.AccountMapper;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.auth.service.RefreshTokenService;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.security.AccessTokenDenylist;
import com.waller.wallet_platform.security.AccountAccessChecker;
import com.waller.wallet_platform.security.JwtTokenProvider;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.mapper.AppUserMapper;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class AccountServiceTest {

    // TODO: AccountServiceImpl only takes accountRepository, userRepository, accountMapper, accountAccessChecker and
    //  outboxEventService. Remove the other mocks (userMapper, passwordEncoder, jwtTokenProvider, refreshTokenService,
    //  accessTokenDenylist); they look like dependencies but are never used.
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private AccountMapper accountMapper;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private AppUserMapper userMapper;
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
    @Mock
    private AccountAccessChecker accessChecker;

    @InjectMocks
    private AccountServiceImpl service;

    private Account testAccount;
    private AccountDto accountDto;
    private AppUser user;


    @BeforeEach
    void setUp() {
        user = new AppUser();
        user.setName("TestUser");
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
    }

    @Test
    void getIdByAccountExistReturnAccountDto() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(testAccount));
        when(accessChecker.canAccess(testAccount)).thenReturn(true);
        when(accountMapper.toAccountDto(testAccount)).thenReturn(accountDto);
        // TODO: Remove the log line and @Slf4j. Also, the assertEquals below compare the mapper's own stub to itself;
        //  assertThat(result).isEqualTo(accountDto) says the same thing in one line.
        log.info("ACCOUNT {}",testAccount.getId());

        AccountDto result = service.getAccount(1L);

        assertNotNull(result);
        assertEquals(accountDto.id(),result.id());
        assertEquals(accountDto.status(),result.status());
        assertEquals(accountDto.userId(),result.userId());
        assertEquals(accountDto.currency(),result.currency());
        assertEquals(accountDto.balance(),result.balance());

        verify(accountRepository, times(1)).findById(1L);
        verify(accountMapper, times(1)).toAccountDto(testAccount);

    }

    @Test
    void getByIdAndAccountNotFoundThrowsException() {
        when(accountRepository.findById(1000L)).thenReturn(Optional.empty());

        assertThatThrownBy(()-> service.getAccount(1000L)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Account not found");

        verify(accountRepository, times(1)).findById(1000L);
    }

    // TODO: Missing cases:
    //  - getAccount when the account exists but accessChecker.canAccess is false gives the same "Account not found".
    //  - createAnAccount: happy path (records the outbox event), and an unknown user id.
    //  - getMyAccounts.
}
