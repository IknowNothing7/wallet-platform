package com.waller.wallet_platform.transfer.service;

import static com.waller.wallet_platform.support.TestData.account;
import static com.waller.wallet_platform.support.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.transfer.dto.TransferDto;
import com.waller.wallet_platform.transfer.dto.TransferRequest;
import com.waller.wallet_platform.transfer.entity.TransferLimit;
import com.waller.wallet_platform.transfer.entity.TransferStatus;
import com.waller.wallet_platform.transfer.repository.TransferLimitRepository;
import com.waller.wallet_platform.transfer.repository.TransferRepository;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import com.waller.wallet_platform.support.IntegrationTest;

class TransferLimitTest extends IntegrationTest {

    @Autowired
    private TransferProcessingService transferProcessingService;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransferLimitRepository transferLimitRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    private Account aliceUsd;
    private Account bobUsd;

    @BeforeEach
    void setUp() {
        transferLimitRepository.deleteAll();

        AppUser alice = appUserRepository.save(user("Alice", "alice@email.com", UserRole.USER));
        AppUser bob = appUserRepository.save(user("Bob", "bob@email.com", UserRole.USER));
        aliceUsd = accountRepository.save(account(alice, 10_000, "USD"));
        bobUsd = accountRepository.save(account(bob, 0, "USD"));
    }

    @Test
    void overPerTransferMax_fails() {
        transferLimitRepository.save(currencyLimit("USD", 1_000L, null));

        TransferDto result = process(1_001);

        assertThat(result.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo("Amount exceeds the per-transfer limit");
        assertThat(accountRepository.findById(aliceUsd.getId()).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @Test
    void dailyMax_countsEarlierCompletedTransfers() {
        transferLimitRepository.save(currencyLimit("USD", null, 1_000L));

        assertThat(process(600).status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(process(400).status()).isEqualTo(TransferStatus.COMPLETED);

        TransferDto third = process(1);
        assertThat(third.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(third.failureReason()).isEqualTo("Amount exceeds the daily transfer limit");
    }

    @Test
    void accountOverride_replacesCurrencyDefault() {
        transferLimitRepository.save(currencyLimit("USD", 100L, 100L));
        TransferLimit override = new TransferLimit();
        override.setAccount(aliceUsd);
        override.setPerTransferMax(5_000L);
        transferLimitRepository.save(override);

        assertThat(process(5_000).status()).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    // Fail closed: without a limit row the currency is not enabled for transfers at all
    void noLimitRow_refusesTransfer() {
        TransferDto result = process(100);

        assertThat(result.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo("Transfers in USD are not enabled");
    }

    private TransferDto process(long amount) {
        return transferProcessingService.book(new TransferRequest(aliceUsd.getId(), bobUsd.getId(), amount, "USD"));
    }

    private static TransferLimit currencyLimit(String currency, Long perTransferMax, Long dailyMax) {
        TransferLimit limit = new TransferLimit();
        limit.setCurrency(currency);
        limit.setPerTransferMax(perTransferMax);
        limit.setDailyMax(dailyMax);
        return limit;
    }
}
