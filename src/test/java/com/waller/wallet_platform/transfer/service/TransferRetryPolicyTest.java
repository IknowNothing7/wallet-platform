package com.waller.wallet_platform.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class TransferRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final TransferRetryPolicy policy = new TransferRetryPolicy(3, Duration.ofSeconds(5), Duration.ofSeconds(30));

    @Test
    void backoffDoublesPerAttemptUpToMax() {
        assertThat(policy.nextAttemptAt(1, NOW)).isEqualTo(NOW.plusSeconds(5));
        assertThat(policy.nextAttemptAt(2, NOW)).isEqualTo(NOW.plusSeconds(10));
        assertThat(policy.nextAttemptAt(3, NOW)).isEqualTo(NOW.plusSeconds(20));
        assertThat(policy.nextAttemptAt(4, NOW)).isEqualTo(NOW.plusSeconds(30));
        assertThat(policy.nextAttemptAt(1_000, NOW)).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void exhaustedAtMaxAttempts() {
        assertThat(policy.exhausted(2)).isFalse();
        assertThat(policy.exhausted(3)).isTrue();
    }
}
