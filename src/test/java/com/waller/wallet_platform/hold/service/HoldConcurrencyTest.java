package com.waller.wallet_platform.hold.service;

import static com.waller.wallet_platform.support.TestData.account;
import static com.waller.wallet_platform.support.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.hold.dto.HoldDto;
import com.waller.wallet_platform.hold.dto.HoldRequest;
import com.waller.wallet_platform.hold.entity.HoldStatus;
import com.waller.wallet_platform.hold.repository.HoldRepository;
import com.waller.wallet_platform.support.IntegrationTest;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;

// The account row lock (findUserAccountByIdForUpdate) and the active-hold sum against real Postgres: two holds that
// each fit the balance on their own, but not together, race and only one may win.
class HoldConcurrencyTest extends IntegrationTest {

    private static final String MERCHANT_EMAIL = "merchant@email.com";

    @Autowired
    private HoldService holdService;

    @Autowired
    private HoldRepository holdRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Test
    void twoHoldsRacingForTheLastOfTheBalance_onlyOneSucceeds() throws Exception {
        AppUser customer = appUserRepository.save(user("customer", "customer@email.com", UserRole.USER));
        appUserRepository.save(user("merchant", MERCHANT_EMAIL, UserRole.MERCHANT));
        Account account = accountRepository.save(account(customer, 100, "USD"));
        HoldRequest request = new HoldRequest(account.getId(), 60L, "USD", Instant.now().plus(Duration.ofHours(1)));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<HoldDto>> attempts = List.of(
                    CompletableFuture.supplyAsync(() -> createAfter(start, request), pool),
                    CompletableFuture.supplyAsync(() -> createAfter(start, request), pool));
            start.countDown();

            List<Object> outcomes = attempts.stream().map(HoldConcurrencyTest::outcome).toList();

            assertThat(outcomes).filteredOn(HoldDto.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(ResponseStatusException.class::isInstance)
                    .singleElement()
                    .satisfies(e -> {
                        ResponseStatusException conflict = (ResponseStatusException) e;
                        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(conflict.getReason()).isEqualTo("There is not enough money on the account");
                    });
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(holdRepository.findAll())
                .singleElement()
                .satisfies(hold -> {
                    assertThat(hold.getAccount().getId()).isEqualTo(account.getId());
                    assertThat(hold.getAmount()).isEqualTo(60L);
                });
        assertThat(holdRepository.sumAmountByAccountIdAndStatus(account.getId(), HoldStatus.ACTIVE, Instant.now()))
                .isEqualTo(60L);
    }

    private HoldDto createAfter(CountDownLatch start, HoldRequest request) {
        try {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        // createHold records the caller as the hold's merchant; the security context is per thread
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(MERCHANT_EMAIL, null));
        try {
            return holdService.createHold(request);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // The created hold, or the exception the attempt failed with
    private static Object outcome(CompletableFuture<HoldDto> attempt) {
        try {
            return attempt.orTimeout(30, TimeUnit.SECONDS).join();
        } catch (CompletionException e) {
            return e.getCause();
        }
    }
}
