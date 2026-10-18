package com.waller.wallet_platform.deposit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.AccountStatus;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.deposit.dto.DepositDto;
import com.waller.wallet_platform.deposit.dto.DepositRequest;
import com.waller.wallet_platform.deposit.entity.Deposit;
import com.waller.wallet_platform.deposit.entity.DepositStatus;
import com.waller.wallet_platform.deposit.mapper.DepositMapper;
import com.waller.wallet_platform.deposit.repository.DepositRepository;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.security.AccountAccessChecker;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static com.waller.wallet_platform.support.TestData.deposit;

@ExtendWith(MockitoExtension.class)
public class DepositServiceTest {

    @Mock
    private DepositRepository repository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private DepositMapper mapper;

    @Mock
    private AccountAccessChecker accountAccessChecker;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private DepositServiceImpl depositService;

    private Deposit deposit;
    private DepositDto depositDto;
    private DepositRequest request;
    private Account account;
    private AppUser appUser;

    @BeforeEach
    void setUp() {

        appUser = new AppUser();
        appUser.setId(1L);
        appUser.setRole(UserRole.USER);

        account = new Account();
        account.setId(1L);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCurrency("USD");
        account.setUser(appUser);

        request = DepositRequest.builder()
                .accountId(account.getId())
                .currency(account.getCurrency())
                .amount(100L)
                .build();

        depositDto = DepositDto.builder()
                .accountId(account.getId())
                .amount(100L)
                .currency("USD")
                .id(2L)
                .build();

        deposit = deposit(account, request.getAmount(), request.getCurrency(), DepositStatus.PENDING, null, null);

        ReflectionTestUtils.setField(depositService, "pendingTtl", Duration.ofMinutes(30));
    }

    @Test
    void createDepositTest() {
        when(accountRepository.findUserAccountById(account.getId())).thenReturn(Optional.of(account));
        when(accountAccessChecker.canAccess(account)).thenReturn(true);
        when(mapper.requestToEntity(request)).thenReturn(deposit);
        when(mapper.toDto(deposit)).thenReturn(depositDto);
        when(repository.save(deposit)).thenReturn(deposit);
        Instant before = Instant.now();
        DepositDto  result = depositService.createDeposit(request);

        assertThat(deposit.getExpiresAt()).isBetween(before.plus(Duration.ofMinutes(30)), Instant.now().plus(Duration.ofMinutes(30)));

        assertNotNull(result);

        assertEquals(deposit.getAccount().getId(), result.accountId());
        assertEquals(deposit.getAmount(), result.amount());
        assertEquals(DepositStatus.PENDING, deposit.getStatus());
        assertEquals(deposit.getCurrency(), result.currency());

        verify(accountRepository,times(1)).findUserAccountById(account.getId());
        verify(mapper,times(1)).requestToEntity(request);
        verify(repository,times(1)).save(deposit);
        verify(outboxEventService).record("deposit", deposit.getId(), "DepositCreated", depositDto);
        verify(eventPublisher).publishEvent(new DepositCreatedEvent(deposit.getId()));
    }

    @Test
    void ifAccountNotFoundThrowAnException() {
        when(accountRepository.findUserAccountById(request.getAccountId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> depositService.createDeposit(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Account 1 not found");

        verify(accountRepository,times(1)).findUserAccountById(request.getAccountId());
        verifyNoInteractions(repository, outboxEventService, eventPublisher);
    }

    // Someone else's account answers exactly like a missing one, so account ids can't be probed
    @Test
    void createDeposit_someoneElsesAccount_throwsNotFoundAndSavesNothing() {
        when(accountRepository.findUserAccountById(account.getId())).thenReturn(Optional.of(account));
        when(accountAccessChecker.canAccess(account)).thenReturn(false);

        assertThatThrownBy(() -> depositService.createDeposit(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Account 1 not found");

        verifyNoInteractions(repository, mapper, outboxEventService, eventPublisher);
    }

    @Test
    void getAccountDeposits_returnsMappedPage() {
        int page = 1, size = 10;
        Pageable pageable = PageRequest.of(page, size);
        Page<Deposit> deposits = new PageImpl<>(List.of(deposit), pageable, 11);

        when(accountAccessChecker.canAccess(account.getId())).thenReturn(true);
        when(repository.findByAccountIdOrderByCreatedAtDescIdDesc(account.getId(), pageable)).thenReturn(deposits);
        when(mapper.toDto(deposit)).thenReturn(depositDto);

        PageResponse<DepositDto> result = depositService.getAccountDeposits(account.getId(), page, size);

        assertEquals(List.of(depositDto), result.content());
        assertEquals(page, result.page());
        assertEquals(size, result.size());
        assertEquals(11, result.totalElements());
        assertEquals(2, result.totalPages());

        verify(repository).findByAccountIdOrderByCreatedAtDescIdDesc(account.getId(), pageable);
    }

    @Test
    void getAccountDeposits_noAccess_throwsNotFound() {
        when(accountAccessChecker.canAccess(account.getId())).thenReturn(false);

        assertThatThrownBy(() -> depositService.getAccountDeposits(account.getId(), 0, 10))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Account not found");

        verifyNoInteractions(repository, mapper);
    }

    @Test
    void createDeposit_currencyMismatch_throwsBadRequestAndSavesNothing() {
        request.setCurrency("EUR");
        when(accountRepository.findUserAccountById(account.getId())).thenReturn(Optional.of(account));
        when(accountAccessChecker.canAccess(account)).thenReturn(true);

        assertThatThrownBy(() -> depositService.createDeposit(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("does not match account currency USD");

        verifyNoInteractions(repository, mapper, outboxEventService, eventPublisher);
    }

    // Deposits go only into ACTIVE accounts, like holds (HoldServiceImpl)
    @Test
    void createDeposit_frozenAccount_throwsForbiddenAndSavesNothing() {
        account.setStatus(AccountStatus.FROZEN);
        when(accountRepository.findUserAccountById(account.getId())).thenReturn(Optional.of(account));
        when(accountAccessChecker.canAccess(account)).thenReturn(true);

        assertThatThrownBy(() -> depositService.createDeposit(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("not active");

        verifyNoInteractions(repository, mapper, outboxEventService, eventPublisher);
    }

    @Test
    void getDeposit_ownDeposit_returnsDto() {
        deposit.setId(2L);
        when(repository.findWithAccountById(2L)).thenReturn(Optional.of(deposit));
        when(accountAccessChecker.canAccess(account)).thenReturn(true);
        when(mapper.toDto(deposit)).thenReturn(depositDto);

        DepositDto result = depositService.getDeposit(2L);

        assertThat(result).isEqualTo(depositDto);
    }

    @Test
    void getDeposit_someoneElsesDeposit_throwsNotFound() {
        deposit.setId(2L);
        when(repository.findWithAccountById(2L)).thenReturn(Optional.of(deposit));
        when(accountAccessChecker.canAccess(account)).thenReturn(false);

        assertThatThrownBy(() -> depositService.getDeposit(2L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Deposit not found");

        verifyNoInteractions(mapper);
    }

    @Test
    void getDeposit_missing_throwsNotFound() {
        when(repository.findWithAccountById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> depositService.getDeposit(99L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Deposit not found");

        verifyNoInteractions(accountAccessChecker, mapper);
    }
}
