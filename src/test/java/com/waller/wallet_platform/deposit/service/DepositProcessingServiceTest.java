package com.waller.wallet_platform.deposit.service;

import static com.waller.wallet_platform.support.TestData.deposit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import com.waller.wallet_platform.account.service.SystemAccountService;
import com.waller.wallet_platform.bank.client.BankClient;
import com.waller.wallet_platform.bank.client.BankRejectedException;
import com.waller.wallet_platform.bank.dto.BankPayment;
import com.waller.wallet_platform.bank.dto.BankPaymentResult;
import com.waller.wallet_platform.bank.dto.BankPaymentStatus;
import com.waller.wallet_platform.deposit.entity.Deposit;
import com.waller.wallet_platform.deposit.entity.DepositStatus;
import com.waller.wallet_platform.deposit.mapper.DepositMapper;
import com.waller.wallet_platform.deposit.repository.DepositRepository;
import com.waller.wallet_platform.ledger.repository.JournalEntryRepository;
import com.waller.wallet_platform.ledger.service.JournalEntryService;
import com.waller.wallet_platform.outbox.service.OutboxEventService;

@ExtendWith(MockitoExtension.class)
class DepositProcessingServiceTest {

    private static final long DEPOSIT_ID = 7L;

    @Mock
    private DepositRepository depositRepository;

    @Mock
    private DepositMapper depositMapper;

    @Mock
    private BankClient bankClient;

    @Mock
    private SystemAccountService systemAccountService;

    @Mock
    private JournalEntryService journalEntryService;

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private DepositAlerts depositAlerts;

    @InjectMocks
    private DepositProcessingServiceImpl service;

    private Deposit deposit;

    @BeforeEach
    void setUp() {
        deposit = deposit(null, 2500, "USD", DepositStatus.PENDING, "bank-sim", null);
        deposit.setId(DEPOSIT_ID);
        // Lenient: applyResult locks by id and never reads it this way
        lenient().when(depositRepository.findById(DEPOSIT_ID)).thenReturn(Optional.of(deposit));
    }

    @Test
    void submitToBank_rejectedByBank_failsDepositWithReason() {
        stubTransaction();
        when(depositRepository.findByIdForUpdate(DEPOSIT_ID)).thenReturn(Optional.of(deposit));
        when(depositRepository.saveAndFlush(deposit)).thenReturn(deposit);
        when(bankClient.createPayment(eq("deposit-7"), any()))
                .thenThrow(new BankRejectedException(HttpStatus.BAD_REQUEST, "{\"message\":\"bad currency\"}"));

        service.submitToBank(DEPOSIT_ID);

        assertThat(deposit.getStatus()).isEqualTo(DepositStatus.FAILED);
        assertThat(deposit.getFailureReason()).isEqualTo("Rejected by bank (400): {\"message\":\"bad currency\"}");
        assertThat(deposit.getGatewayReference()).isNull();
        verify(outboxEventService).record(eq("deposit"), eq(DEPOSIT_ID), eq("DepositFailed"), any());
    }

    @Test
    void submitToBank_rejectedWithLongBody_truncatesReasonToColumnSize() {
        stubTransaction();
        when(depositRepository.findByIdForUpdate(DEPOSIT_ID)).thenReturn(Optional.of(deposit));
        when(depositRepository.saveAndFlush(deposit)).thenReturn(deposit);
        when(bankClient.createPayment(anyString(), any()))
                .thenThrow(new BankRejectedException(HttpStatus.UNPROCESSABLE_CONTENT, "x".repeat(1000)));

        service.submitToBank(DEPOSIT_ID);

        assertThat(deposit.getFailureReason()).hasSize(255).startsWith("Rejected by bank (422): xxx");
    }

    @Test
    void submitToBank_rejectedButAlreadySettled_isLeftAlone() {
        stubTransaction();
        // Settled by someone else (e.g. expired) between the first read and the lock
        Deposit expired = deposit(null, 2500, "USD", DepositStatus.EXPIRED, "bank-sim", null);
        expired.setId(DEPOSIT_ID);
        when(depositRepository.findByIdForUpdate(DEPOSIT_ID)).thenReturn(Optional.of(expired));
        when(bankClient.createPayment(anyString(), any()))
                .thenThrow(new BankRejectedException(HttpStatus.BAD_REQUEST, "{}"));

        service.submitToBank(DEPOSIT_ID);

        assertThat(expired.getStatus()).isEqualTo(DepositStatus.EXPIRED);
        verify(outboxEventService, never()).record(anyString(), any(), anyString(), any());
    }

    @Test
    void submitToBank_serverError_propagatesAndKeepsDepositPending() {
        when(bankClient.createPayment(anyString(), any()))
                .thenThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                        null, null, null));

        assertThatThrownBy(() -> service.submitToBank(DEPOSIT_ID)).isInstanceOf(HttpServerErrorException.class);

        assertThat(deposit.getStatus()).isEqualTo(DepositStatus.PENDING);
        assertThat(deposit.getFailureReason()).isNull();
        verify(depositRepository, never()).saveAndFlush(any());
        verify(outboxEventService, never()).record(anyString(), any(), anyString(), any());
    }

    @Test
    void expire_cancelledAtBank_expiresDeposit() {
        Deposit pending = expiredPendingDeposit();
        when(bankClient.cancelPayment("bsim_1")).thenReturn(bankPayment(BankPaymentStatus.CANCELLED));
        when(depositRepository.findByIdForUpdate(DEPOSIT_ID)).thenReturn(Optional.of(pending));
        stubTransactionWithoutResult();

        service.expire(DEPOSIT_ID);

        assertThat(pending.getStatus()).isEqualTo(DepositStatus.EXPIRED);
        verify(outboxEventService).record(eq("deposit"), eq(DEPOSIT_ID), eq("DepositExpired"), any());
    }

    @Test
    void expire_settledBeforeCancel_appliesBankResult() {
        Deposit pending = expiredPendingDeposit();
        when(bankClient.cancelPayment("bsim_1")).thenReturn(bankPayment(BankPaymentStatus.FAILED));
        when(depositRepository.findByIdForUpdate(DEPOSIT_ID)).thenReturn(Optional.of(pending));
        stubTransactionWithoutResult();

        service.expire(DEPOSIT_ID);

        assertThat(pending.getStatus()).isEqualTo(DepositStatus.FAILED);
        verify(outboxEventService).record(eq("deposit"), eq(DEPOSIT_ID), eq("DepositFailed"), any());
    }

    @Test
    void expire_unknownAtBank_expiresDeposit() {
        Deposit pending = expiredPendingDeposit();
        when(bankClient.cancelPayment("bsim_1")).thenThrow(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));
        when(depositRepository.findByIdForUpdate(DEPOSIT_ID)).thenReturn(Optional.of(pending));
        stubTransactionWithoutResult();

        service.expire(DEPOSIT_ID);

        assertThat(pending.getStatus()).isEqualTo(DepositStatus.EXPIRED);
    }

    @Test
    void applyResult_cancelledForExpiredDeposit_isNotAConflict() {
        Deposit expired = expiredPendingDeposit();
        expired.setStatus(DepositStatus.EXPIRED);
        when(depositRepository.findByIdForUpdate(DEPOSIT_ID)).thenReturn(Optional.of(expired));

        service.applyResult(new BankPaymentResult("bsim_1", String.valueOf(DEPOSIT_ID), BankPaymentStatus.CANCELLED,
                2500, "USD", "Cancelled by merchant", Instant.now()));

        verifyNoInteractions(depositAlerts);
        verify(outboxEventService, never()).record(anyString(), any(), anyString(), any());
    }

    private Deposit expiredPendingDeposit() {
        deposit.setGatewayReference("bsim_1");
        deposit.setExpiresAt(Instant.now().minusSeconds(60));
        return deposit;
    }

    private static BankPayment bankPayment(BankPaymentStatus status) {
        String reason = status == BankPaymentStatus.PENDING || status == BankPaymentStatus.SUCCEEDED
                ? null : "Declined by issuer";
        return new BankPayment("bsim_1", 2500, "USD", String.valueOf(DEPOSIT_ID), status, reason, Instant.now());
    }

    private void stubTransactionWithoutResult() {
        doAnswer(invocation -> {
            invocation.<Consumer<TransactionStatus>>getArgument(0).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private void stubTransaction() {
        when(transactionTemplate.execute(any())).thenAnswer(
                invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
    }
}
