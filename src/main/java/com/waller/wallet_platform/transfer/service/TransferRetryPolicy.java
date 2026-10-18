package com.waller.wallet_platform.transfer.service;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * When to retry a transfer whose processing threw, and when to give up on it. The backoff doubles per attempt, so
 * a short outage (e.g. lock timeouts) is ridden out, while a row that can never be processed ends as FAILED.
 */
@Component
public class TransferRetryPolicy {

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    public TransferRetryPolicy(
            @Value("${transfer.processing.max-attempts:10}") int maxAttempts,
            @Value("${transfer.processing.initial-backoff:PT5S}") Duration initialBackoff,
            @Value("${transfer.processing.max-backoff:PT5M}") Duration maxBackoff) {
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public boolean exhausted(int attempts) {
        return attempts >= maxAttempts;
    }

    /** Next try after the given number of failed attempts (1 or more). */
    public Instant nextAttemptAt(int attempts, Instant now) {
        // Capped shift so a large attempt count can't overflow
        Duration backoff = initialBackoff.multipliedBy(1L << Math.min(attempts - 1, 20));
        return now.plus(backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff);
    }
}
