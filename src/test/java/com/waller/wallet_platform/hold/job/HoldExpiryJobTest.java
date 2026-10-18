package com.waller.wallet_platform.hold.job;

import static com.waller.wallet_platform.support.TestData.account;
import static com.waller.wallet_platform.support.TestData.hold;
import static com.waller.wallet_platform.support.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.hold.entity.Hold;
import com.waller.wallet_platform.hold.entity.HoldStatus;
import com.waller.wallet_platform.hold.repository.HoldRepository;
import com.waller.wallet_platform.outbox.entity.OutboxEvent;
import com.waller.wallet_platform.outbox.repository.OutboxRepository;
import com.waller.wallet_platform.support.IntegrationTest;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;

// Run by hand: IntegrationTest pushes the schedule out of the way
class HoldExpiryJobTest extends IntegrationTest {

    @Autowired
    private HoldExpiryJob holdExpiryJob;

    @Autowired
    private HoldRepository holdRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    void expiresOnlyActiveHoldsPastExpiresAt_andPublishesHoldExpired() {
        Account account = accountRepository.save(account(
                appUserRepository.save(user("Alice", "alice@email.com", UserRole.USER)), 1_000, "USD"));
        Hold due = holdRepository.save(hold(account, 100, "USD", Instant.now().minus(Duration.ofMinutes(1))));
        Hold notYet = holdRepository.save(hold(account, 100, "USD", Instant.now().plus(Duration.ofHours(1))));
        Hold released = hold(account, 100, "USD", Instant.now().minus(Duration.ofMinutes(1)));
        released.setStatus(HoldStatus.RELEASED);
        released = holdRepository.save(released);

        holdExpiryJob.expireDue();

        assertThat(status(due)).isEqualTo(HoldStatus.EXPIRED);
        assertThat(status(notYet)).isEqualTo(HoldStatus.ACTIVE);
        assertThat(status(released)).isEqualTo(HoldStatus.RELEASED);
        assertThat(outboxRepository.findAll())
                .extracting(OutboxEvent::getEventType, OutboxEvent::getAggregateId)
                .containsExactly(tuple("HoldExpired", due.getId()));
    }

    @Test
    void secondRun_findsNothingMore() {
        Account account = accountRepository.save(account(
                appUserRepository.save(user("Alice", "alice@email.com", UserRole.USER)), 1_000, "USD"));
        holdRepository.save(hold(account, 100, "USD", Instant.now().minus(Duration.ofMinutes(1))));

        holdExpiryJob.expireDue();
        holdExpiryJob.expireDue();

        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    private HoldStatus status(Hold hold) {
        return holdRepository.findById(hold.getId()).orElseThrow().getStatus();
    }
}
