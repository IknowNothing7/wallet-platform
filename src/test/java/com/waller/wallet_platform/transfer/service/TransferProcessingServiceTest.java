package com.waller.wallet_platform.transfer.service;

import static com.waller.wallet_platform.support.TestData.account;
import static com.waller.wallet_platform.support.TestData.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.SystemAccountKind;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.account.service.SystemAccountService;
import com.waller.wallet_platform.hold.entity.HoldStatus;
import com.waller.wallet_platform.hold.repository.HoldRepository;
import com.waller.wallet_platform.ledger.dto.JournalEntryDto;
import com.waller.wallet_platform.ledger.dto.JournalEntryRequest;
import com.waller.wallet_platform.ledger.dto.PostingRequest;
import com.waller.wallet_platform.ledger.entity.JournalEntryType;
import com.waller.wallet_platform.ledger.entity.PostingDirection;
import com.waller.wallet_platform.ledger.repository.JournalEntryRepository;
import com.waller.wallet_platform.ledger.service.JournalEntryService;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.transfer.dto.TransferDto;
import com.waller.wallet_platform.transfer.entity.Transfer;
import com.waller.wallet_platform.transfer.entity.TransferStatus;
import com.waller.wallet_platform.transfer.mapper.TransferMapper;
import com.waller.wallet_platform.transfer.repository.TransferLimitRepository;
import com.waller.wallet_platform.transfer.repository.TransferRepository;

@ExtendWith(MockitoExtension.class)
class TransferProcessingServiceTest {

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private TransferLimitRepository transferLimitRepository;

    @Mock
    private TransferMapper transferMapper;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private HoldRepository holdRepository;

    @Mock
    private JournalEntryService journalEntryService;

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private SystemAccountService systemAccountService;

    @Mock
    private TransferRetryPolicy retryPolicy;

    @InjectMocks
    private TransferProcessingServiceImpl transferProcessingService;

    @Captor
    private ArgumentCaptor<JournalEntryRequest> entryCaptor;

    private Account from;
    private Account to;
    private Account fees;

    @BeforeEach
    void setUp() {
        from = account(1L, "USD");
        from.setBalance(10_000L);
        to = account(2L, "USD");
        fees = account(99L, "USD");
    }

    @Test
    void process_takesTwoPercentFeeFromWhatTheRecipientGets() {
        Transfer transfer = completable(1_000);
        when(holdRepository.sumAmountByAccountIdAndStatus(eq(1L), eq(HoldStatus.ACTIVE), any())).thenReturn(0L);
        when(systemAccountService.get(SystemAccountKind.FEES, "USD")).thenReturn(fees);
        stubJournalEntryAndSave(transfer);

        TransferDto result = transferProcessingService.process(10L);

        assertThat(result.status()).isEqualTo(TransferStatus.COMPLETED);
        JournalEntryRequest entry = entryCaptor.getValue();
        assertThat(entry.getEntryType()).isEqualTo(JournalEntryType.TRANSFER);
        assertThat(entry.getReferenceType()).isEqualTo("transfer");
        assertThat(entry.getReferenceId()).isEqualTo(10L);
        assertThat(entry.getPostings())
                .extracting(PostingRequest::getAccountId, PostingRequest::getDirection, PostingRequest::getAmount)
                .containsExactly(
                        tuple(1L, PostingDirection.DEBIT, 1_000L),
                        tuple(2L, PostingDirection.CREDIT, 980L),
                        tuple(99L, PostingDirection.CREDIT, 20L));
        verify(outboxEventService).record("transfer", 10L, "TransferCompleted", result);
    }

    @Test
    void process_feeRoundsDownInTheUsersFavour() {
        // 2% of 149 is 2.98
        Transfer transfer = completable(149);
        when(holdRepository.sumAmountByAccountIdAndStatus(eq(1L), eq(HoldStatus.ACTIVE), any())).thenReturn(0L);
        when(systemAccountService.get(SystemAccountKind.FEES, "USD")).thenReturn(fees);
        stubJournalEntryAndSave(transfer);

        transferProcessingService.process(10L);

        assertThat(entryCaptor.getValue().getPostings())
                .extracting(PostingRequest::getAccountId, PostingRequest::getAmount)
                .containsExactly(tuple(1L, 149L), tuple(2L, 147L), tuple(99L, 2L));
    }

    @Test
    void process_feeRoundingToZero_getsNoFeePosting() {
        // 2% of 49 is 0.98
        Transfer transfer = completable(49);
        when(holdRepository.sumAmountByAccountIdAndStatus(eq(1L), eq(HoldStatus.ACTIVE), any())).thenReturn(0L);
        stubJournalEntryAndSave(transfer);

        transferProcessingService.process(10L);

        assertThat(entryCaptor.getValue().getPostings())
                .extracting(PostingRequest::getAccountId, PostingRequest::getDirection, PostingRequest::getAmount)
                .containsExactly(
                        tuple(1L, PostingDirection.DEBIT, 49L),
                        tuple(2L, PostingDirection.CREDIT, 49L));
        verifyNoInteractions(systemAccountService);
    }

    @Test
    void reverse_undoesTheWholeEntryFeeIncluded() {
        Transfer transfer = completed(1_000);
        to.setBalance(980L);
        when(holdRepository.sumAmountByAccountIdAndStatus(eq(2L), eq(HoldStatus.ACTIVE), any())).thenReturn(0L);
        when(systemAccountService.get(SystemAccountKind.FEES, "USD")).thenReturn(fees);
        stubJournalEntryAndSave(transfer);

        TransferDto result = transferProcessingService.reverse(10L, "fraud");

        assertThat(result.status()).isEqualTo(TransferStatus.REVERSED);
        JournalEntryRequest entry = entryCaptor.getValue();
        assertThat(entry.getEntryType()).isEqualTo(JournalEntryType.REVERSAL);
        assertThat(entry.getDescription()).isEqualTo("Reversal of transfer 10: fraud");
        assertThat(entry.getReferenceType()).isEqualTo("transfer");
        assertThat(entry.getReferenceId()).isEqualTo(10L);
        assertThat(entry.getPostings())
                .extracting(PostingRequest::getAccountId, PostingRequest::getDirection, PostingRequest::getAmount)
                .containsExactly(
                        tuple(2L, PostingDirection.DEBIT, 980L),
                        tuple(99L, PostingDirection.DEBIT, 20L),
                        tuple(1L, PostingDirection.CREDIT, 1_000L));
        verify(outboxEventService).record("transfer", 10L, "TransferReversed", result);
    }

    @Test
    void reverse_feeRoundingToZero_getsNoFeePosting() {
        Transfer transfer = completed(49);
        to.setBalance(49L);
        when(holdRepository.sumAmountByAccountIdAndStatus(eq(2L), eq(HoldStatus.ACTIVE), any())).thenReturn(0L);
        stubJournalEntryAndSave(transfer);

        transferProcessingService.reverse(10L, null);

        assertThat(entryCaptor.getValue().getDescription()).isEqualTo("Reversal of transfer 10");
        assertThat(entryCaptor.getValue().getPostings())
                .extracting(PostingRequest::getAccountId, PostingRequest::getDirection, PostingRequest::getAmount)
                .containsExactly(
                        tuple(2L, PostingDirection.DEBIT, 49L),
                        tuple(1L, PostingDirection.CREDIT, 49L));
        verifyNoInteractions(systemAccountService);
    }

    @Test
    void reverse_recipientSpentTheMoney_throwsConflictAndBooksNothing() {
        Transfer transfer = completed(1_000);
        to.setBalance(979L);
        when(holdRepository.sumAmountByAccountIdAndStatus(eq(2L), eq(HoldStatus.ACTIVE), any())).thenReturn(0L);

        assertThatThrownBy(() -> transferProcessingService.reverse(10L, "fraud"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("no longer has the funds");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        verifyNoInteractions(journalEntryService, outboxEventService);
        verify(transferRepository, never()).saveAndFlush(any());
    }

    @Test
    void reverse_activeHoldsOnRecipient_countAgainstTheBalance() {
        Transfer transfer = completed(1_000);
        to.setBalance(1_500L);
        when(holdRepository.sumAmountByAccountIdAndStatus(eq(2L), eq(HoldStatus.ACTIVE), any())).thenReturn(521L);

        assertThatThrownBy(() -> transferProcessingService.reverse(10L, "fraud"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        verifyNoInteractions(journalEntryService, outboxEventService);
    }

    @Test
    void reverse_alreadyReversed_returnsItUnchanged() {
        Transfer transfer = transfer(from, to, 1_000, "USD");
        transfer.setId(10L);
        transfer.setStatus(TransferStatus.REVERSED);
        TransferDto dto = TransferDto.builder().id(10L).status(TransferStatus.REVERSED).build();
        when(transferRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(transfer));
        when(transferMapper.toDto(transfer)).thenReturn(dto);

        assertThat(transferProcessingService.reverse(10L, "again")).isSameAs(dto);

        verifyNoInteractions(accountRepository, journalEntryService, outboxEventService);
    }

    @Test
    void reverse_pendingTransfer_throwsConflict() {
        assertReverseRefused(TransferStatus.PENDING);
    }

    @Test
    void reverse_failedTransfer_throwsConflict() {
        assertReverseRefused(TransferStatus.FAILED);
    }

    @Test
    void reverse_unknownTransfer_throwsNotFound() {
        when(transferRepository.findByIdForUpdate(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferProcessingService.reverse(10L, "fraud"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private void assertReverseRefused(TransferStatus status) {
        Transfer transfer = transfer(from, to, 1_000, "USD");
        transfer.setId(10L);
        transfer.setStatus(status);
        when(transferRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> transferProcessingService.reverse(10L, "fraud"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("Transfer is " + status + " and can not be reversed");

        assertThat(transfer.getStatus()).isEqualTo(status);
        verifyNoInteractions(accountRepository, journalEntryService, outboxEventService);
    }

    // A PENDING transfer whose accounts are locked; no limit row applies (the mock returns empty Optionals)
    private Transfer completable(long amount) {
        Transfer transfer = transfer(from, to, amount, "USD");
        transfer.setId(10L);
        stubLocks(transfer);
        return transfer;
    }

    private Transfer completed(long amount) {
        Transfer transfer = completable(amount);
        transfer.setStatus(TransferStatus.COMPLETED);
        return transfer;
    }

    private void stubLocks(Transfer transfer) {
        when(transferRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(transfer));
        when(accountRepository.findUserAccountByIdForUpdate(1L)).thenReturn(Optional.of(from));
        when(accountRepository.findUserAccountByIdForUpdate(2L)).thenReturn(Optional.of(to));
    }

    // The dto mirrors the transfer's status at save time, so the result shows the transition
    private void stubJournalEntryAndSave(Transfer transfer) {
        when(journalEntryService.createJournalEntry(entryCaptor.capture()))
                .thenReturn(JournalEntryDto.builder().id(50L).build());
        when(transferRepository.saveAndFlush(transfer)).thenReturn(transfer);
        when(transferMapper.toDto(transfer)).thenAnswer(invocation -> TransferDto.builder()
                .id(transfer.getId()).amount(transfer.getAmount()).status(transfer.getStatus()).build());
    }
}
