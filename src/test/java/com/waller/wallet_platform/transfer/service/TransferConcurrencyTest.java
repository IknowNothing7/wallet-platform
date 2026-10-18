package com.waller.wallet_platform.transfer.service;

import static com.waller.wallet_platform.support.TestData.account;
import static com.waller.wallet_platform.support.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.SystemAccountKind;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.account.service.SystemAccountService;
import com.waller.wallet_platform.support.IntegrationTest;
import com.waller.wallet_platform.transfer.dto.TransferDto;
import com.waller.wallet_platform.transfer.dto.TransferRequest;
import com.waller.wallet_platform.transfer.entity.TransferStatus;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;

// Transfers are booked in the request, so parallel requests really contend for the same rows. These race 100 of
// them against real Postgres: the account locks must serialize them without overdrafts, lost updates or deadlocks.
class TransferConcurrencyTest extends IntegrationTest {

    private static final int TRANSFERS = 100;
    private static final int THREADS = 16;

    @Autowired
    private TransferProcessingService transferProcessingService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SystemAccountService systemAccountService;

    private ExecutorService pool;
    // Created before the race (balance 0), so every account has a known starting point
    private Account fees;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(THREADS);
        fees = systemAccountService.get(SystemAccountKind.FEES, "USD");
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    // 10,000 covers 66 transfers of 150 and not a 67th: exactly 66 may win, whatever the interleaving
    @Test
    void hundredParallelTransfersDrainingOneAccount_neverOverdraw() throws Exception {
        Account alice = userAccount("alice", 10_000);
        Account bob = userAccount("bob", 0);
        List<TransferRequest> requests = new ArrayList<>();
        for (int i = 0; i < TRANSFERS; i++) {
            requests.add(new TransferRequest(alice.getId(), bob.getId(), 150L, "USD"));
        }

        List<TransferDto> results = bookAllAtOnce(requests);

        assertThat(results).filteredOn(t -> t.status() == TransferStatus.COMPLETED).hasSize(66);
        assertThat(results).filteredOn(t -> t.status() == TransferStatus.FAILED)
                .hasSize(34)
                .allSatisfy(t -> assertThat(t.failureReason()).isEqualTo("Insufficient funds"));
        assertThat(balance(alice)).isEqualTo(10_000 - 66 * 150);
        // 2% fee: 3 of every 150
        assertThat(balance(bob)).isEqualTo(66 * 147);
        assertThat(balance(fees)).isEqualTo(66 * 3);
        assertBalancesMatchPostings(alice, bob, fees);
    }

    // Transfers in both directions between the same accounts lock the same rows in opposite orders unless the lock
    // order is fixed; any deadlock or lock failure would surface as an exception from bookAllAtOnce
    @Test
    void hundredParallelTransfersInEveryDirection_noDeadlocksAndNoMoneyCreated() throws Exception {
        List<Account> accounts = List.of(userAccount("a", 3_000), userAccount("b", 3_000), userAccount("c", 3_000));
        Random random = new Random(42);
        List<TransferRequest> requests = new ArrayList<>();
        for (int i = 0; i < TRANSFERS; i++) {
            int from = random.nextInt(3);
            int to = (from + 1 + random.nextInt(2)) % 3;
            requests.add(new TransferRequest(
                    accounts.get(from).getId(), accounts.get(to).getId(), 50L + random.nextInt(450), "USD"));
        }

        List<TransferDto> results = bookAllAtOnce(requests);

        assertThat(results).hasSize(TRANSFERS)
                .allSatisfy(t -> assertThat(t.status()).isIn(TransferStatus.COMPLETED, TransferStatus.FAILED));
        long total = accounts.stream().mapToLong(this::balance).sum() + balance(fees);
        assertThat(total).isEqualTo(9_000);
        assertThat(accounts).allSatisfy(a -> assertThat(balance(a)).isNotNegative());
        assertBalancesMatchPostings(accounts.get(0), accounts.get(1), accounts.get(2), fees);
    }

    private List<TransferDto> bookAllAtOnce(List<TransferRequest> requests) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<TransferDto>> futures = requests.stream()
                .map(request -> CompletableFuture.supplyAsync(() -> {
                    awaitUninterruptibly(start);
                    return transferProcessingService.book(request);
                }, pool))
                .toList();
        start.countDown();
        // join() rethrows the first failure, so a deadlock or a lock error fails the test here
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // Starting balances were set directly, so each account's postings must add up to how far it moved since
    private void assertBalancesMatchPostings(Account... accounts) {
        for (Account account : accounts) {
            Long net = jdbcTemplate.queryForObject("""
                    SELECT coalesce(sum(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
                    FROM posting WHERE account_id = ?""", Long.class, account.getId());
            assertThat(balance(account) - account.getBalance())
                    .as("account %d: balance change vs. its postings", account.getId())
                    .isEqualTo(net);
        }
    }

    private Account userAccount(String name, long balance) {
        return accountRepository.save(account(
                appUserRepository.save(user(name, name + "@email.com", UserRole.USER)), balance, "USD"));
    }

    private long balance(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }
}
