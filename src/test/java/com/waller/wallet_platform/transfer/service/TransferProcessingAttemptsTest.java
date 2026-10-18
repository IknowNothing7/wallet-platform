package com.waller.wallet_platform.transfer.service;

import static com.waller.wallet_platform.support.TestData.account;
import static com.waller.wallet_platform.support.TestData.transfer;
import static com.waller.wallet_platform.support.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.support.IntegrationTest;
import com.waller.wallet_platform.transfer.dto.TransferDto;
import com.waller.wallet_platform.transfer.entity.Transfer;
import com.waller.wallet_platform.transfer.entity.TransferStatus;
import com.waller.wallet_platform.transfer.repository.TransferRepository;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;

class TransferProcessingAttemptsTest extends IntegrationTest {

    @Autowired
    private TransferProcessingService transferProcessingService;

    @Autowired
    private TransferRetryPolicy retryPolicy;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    private Transfer pending;

    @BeforeEach
    void setUp() {
        AppUser alice = appUserRepository.save(user("Alice", "alice@email.com", UserRole.USER));
        AppUser bob = appUserRepository.save(user("Bob", "bob@email.com", UserRole.USER));
        Account aliceUsd = accountRepository.save(account(alice, 10_000, "USD"));
        Account bobUsd = accountRepository.save(account(bob, 0, "USD"));
        pending = transferRepository.save(transfer(aliceUsd, bobUsd, 100, "USD"));
    }

    @Test
    void failedAttempt_countsAndBacksOff() {
        Instant before = Instant.now();

        TransferDto result = transferProcessingService.recordFailedAttempt(pending.getId(), "lock timeout");

        assertThat(result.status()).isEqualTo(TransferStatus.PENDING);
        Transfer saved = transferRepository.findById(pending.getId()).orElseThrow();
        assertThat(saved.getAttempts()).isEqualTo(1);
        assertThat(saved.getNextAttemptAt()).isAfter(before);
    }

    @Test
    void backingOffTransfer_isNotDue() {
        Instant now = Instant.now();
        transferProcessingService.recordFailedAttempt(pending.getId(), "lock timeout");

        assertThat(transferRepository.findDueIds(
                TransferStatus.PENDING, now.plusSeconds(1), now, PageRequest.of(0, 10))).isEmpty();
        Instant later = retryPolicy.nextAttemptAt(1, now).plusSeconds(1);
        assertThat(transferRepository.findDueIds(
                TransferStatus.PENDING, later, later, PageRequest.of(0, 10))).containsExactly(pending.getId());
    }

    @Test
    void lastAttempt_failsTransfer() {
        TransferDto result = null;
        for (int i = 0; i < retryPolicy.maxAttempts(); i++) {
            result = transferProcessingService.recordFailedAttempt(pending.getId(), "lock timeout");
        }

        assertThat(result.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.failureReason())
                .isEqualTo("Processing failed after " + retryPolicy.maxAttempts() + " attempts: lock timeout");
        assertThat(transferRepository.findById(pending.getId()).orElseThrow().getNextAttemptAt()).isNull();
    }

    @Test
    void longError_isTruncatedToColumnLength() {
        for (int i = 0; i < retryPolicy.maxAttempts(); i++) {
            transferProcessingService.recordFailedAttempt(pending.getId(), "x".repeat(1_000));
        }

        assertThat(transferRepository.findById(pending.getId()).orElseThrow().getFailureReason()).hasSize(255);
    }

    @Test
    void transferNoLongerPending_isUnchanged() {
        assertThat(transferProcessingService.process(pending.getId()).status()).isEqualTo(TransferStatus.COMPLETED);

        TransferDto result = transferProcessingService.recordFailedAttempt(pending.getId(), "lock timeout");

        assertThat(result.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transferRepository.findById(pending.getId()).orElseThrow().getAttempts()).isZero();
    }
}
