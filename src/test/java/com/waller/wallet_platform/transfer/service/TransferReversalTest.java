package com.waller.wallet_platform.transfer.service;

import static com.waller.wallet_platform.support.TestData.account;
import static com.waller.wallet_platform.support.TestData.hold;
import static com.waller.wallet_platform.support.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.SystemAccountKind;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.hold.repository.HoldRepository;
import com.waller.wallet_platform.ledger.dto.JournalEntryDto;
import com.waller.wallet_platform.ledger.entity.JournalEntryType;
import com.waller.wallet_platform.ledger.service.JournalEntryService;
import com.waller.wallet_platform.outbox.entity.OutboxEvent;
import com.waller.wallet_platform.outbox.repository.OutboxRepository;
import com.waller.wallet_platform.support.IntegrationTest;
import com.waller.wallet_platform.transfer.dto.TransferDto;
import com.waller.wallet_platform.transfer.dto.TransferRequest;
import com.waller.wallet_platform.transfer.entity.TransferStatus;
import com.waller.wallet_platform.transfer.repository.TransferRepository;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;

// Fees and reversals against real balances: PostingServiceImpl applies the postings to the accounts
class TransferReversalTest extends IntegrationTest {

    @Autowired
    private TransferProcessingService transferProcessingService;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private HoldRepository holdRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JournalEntryService journalEntryService;

    private Account aliceUsd;
    private Account bobUsd;

    @BeforeEach
    void setUp() {
        AppUser alice = appUserRepository.save(user("Alice", "alice@email.com", UserRole.USER));
        AppUser bob = appUserRepository.save(user("Bob", "bob@email.com", UserRole.USER));
        aliceUsd = accountRepository.save(account(alice, 10_000, "USD"));
        bobUsd = accountRepository.save(account(bob, 0, "USD"));
    }

    @Test
    void process_creditsFeeToFeesAccount() {
        TransferDto result = process(aliceUsd, bobUsd, 1_000);

        assertThat(result.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(balance(aliceUsd)).isEqualTo(9_000);
        assertThat(balance(bobUsd)).isEqualTo(980);
        assertThat(feesBalance()).isEqualTo(20);
    }

    @Test
    void process_feeRoundingToZero_recipientGetsEverything() {
        TransferDto result = process(aliceUsd, bobUsd, 49);

        assertThat(result.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(balance(aliceUsd)).isEqualTo(9_951);
        assertThat(balance(bobUsd)).isEqualTo(49);
        // No fee posting, so the fees account was never needed
        assertThat(accountRepository.findBySystemKindAndCurrency(SystemAccountKind.FEES, "USD")).isEmpty();
    }

    @Test
    void reverse_restoresBalancesAndRefundsFee() {
        TransferDto completed = process(aliceUsd, bobUsd, 1_000);

        TransferDto result = transferProcessingService.reverse(completed.id(), "fraud");

        assertThat(result.status()).isEqualTo(TransferStatus.REVERSED);
        assertThat(transferRepository.findById(completed.id()).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.REVERSED);
        assertThat(balance(aliceUsd)).isEqualTo(10_000);
        assertThat(balance(bobUsd)).isZero();
        assertThat(feesBalance()).isZero();

        List<JournalEntryDto> entries = journalEntryService.getJournalEntriesByReference("transfer", completed.id());
        assertThat(entries).extracting(JournalEntryDto::entryType)
                .containsExactly(JournalEntryType.TRANSFER, JournalEntryType.REVERSAL);
        assertThat(entries.get(1).description()).isEqualTo("Reversal of transfer " + completed.id() + ": fraud");

        List<OutboxEvent> reversed = outboxRepository.findAll().stream()
                .filter(e -> e.getEventType().equals("TransferReversed"))
                .toList();
        assertThat(reversed).hasSize(1);
        assertThat(reversed.getFirst().getAggregateType()).isEqualTo("transfer");
        assertThat(reversed.getFirst().getAggregateId()).isEqualTo(completed.id());
        assertThat(reversed.getFirst().getPayload().get("status").asText()).isEqualTo("REVERSED");
    }

    @Test
    void reverse_twice_booksTheReversalOnce() {
        TransferDto completed = process(aliceUsd, bobUsd, 1_000);
        transferProcessingService.reverse(completed.id(), "fraud");

        TransferDto again = transferProcessingService.reverse(completed.id(), "fraud");

        assertThat(again.status()).isEqualTo(TransferStatus.REVERSED);
        assertThat(balance(aliceUsd)).isEqualTo(10_000);
        assertThat(journalEntryService.getJournalEntriesByReference("transfer", completed.id())).hasSize(2);
    }

    @Test
    void reverse_feeRoundingToZero_restoresBalances() {
        TransferDto completed = process(aliceUsd, bobUsd, 49);

        transferProcessingService.reverse(completed.id(), "mistake");

        assertThat(balance(aliceUsd)).isEqualTo(10_000);
        assertThat(balance(bobUsd)).isZero();
    }

    @Test
    void reverse_recipientSpentTheMoney_returns409AndTransferStaysCompleted() {
        TransferDto completed = process(aliceUsd, bobUsd, 1_000);
        Account carolUsd = accountRepository.save(account(
                appUserRepository.save(user("Carol", "carol@email.com", UserRole.USER)), 0, "USD"));
        process(bobUsd, carolUsd, 100);

        assertConflict(completed.id());

        assertThat(balance(aliceUsd)).isEqualTo(9_000);
        assertThat(balance(bobUsd)).isEqualTo(880);
        // 20 from the original transfer, 2 from Bob's
        assertThat(feesBalance()).isEqualTo(22);
    }

    @Test
    void reverse_recipientMoneyReservedByActiveHold_returns409() {
        TransferDto completed = process(aliceUsd, bobUsd, 1_000);
        holdRepository.save(hold(bobUsd, 1, "USD", Instant.now().plus(1, ChronoUnit.HOURS)));

        assertConflict(completed.id());

        assertThat(balance(bobUsd)).isEqualTo(980);
        assertThat(feesBalance()).isEqualTo(20);
    }

    @Test
    void reverse_expiredHoldDoesNotCount() {
        TransferDto completed = process(aliceUsd, bobUsd, 1_000);
        holdRepository.save(hold(bobUsd, 500, "USD", Instant.now().minus(1, ChronoUnit.HOURS)));

        assertThat(transferProcessingService.reverse(completed.id(), "fraud").status())
                .isEqualTo(TransferStatus.REVERSED);
    }

    @Test
    void reverse_failedTransfer_returns409() {
        TransferDto failed = process(aliceUsd, bobUsd, 20_000);
        assertThat(failed.status()).isEqualTo(TransferStatus.FAILED);

        assertThatThrownBy(() -> transferProcessingService.reverse(failed.id(), "fraud"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(transferRepository.findById(failed.id()).orElseThrow().getStatus()).isEqualTo(TransferStatus.FAILED);
    }

    private void assertConflict(Long transferId) {
        assertThatThrownBy(() -> transferProcessingService.reverse(transferId, "fraud"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("Destination account no longer has the funds to reverse this transfer");

        assertThat(transferRepository.findById(transferId).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.COMPLETED);
        assertThat(journalEntryService.getJournalEntriesByReference("transfer", transferId)).hasSize(1);
        assertThat(outboxRepository.findAll()).noneMatch(e -> e.getEventType().equals("TransferReversed"));
    }

    private TransferDto process(Account from, Account to, long amount) {
        return transferProcessingService.book(new TransferRequest(from.getId(), to.getId(), amount, "USD"));
    }

    private long balance(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    private long feesBalance() {
        return accountRepository.findBySystemKindAndCurrency(SystemAccountKind.FEES, "USD").orElseThrow().getBalance();
    }
}
