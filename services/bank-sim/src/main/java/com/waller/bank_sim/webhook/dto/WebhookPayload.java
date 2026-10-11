package com.waller.bank_sim.webhook.dto;

import java.time.Instant;
import com.waller.bank_sim.payment.model.PaymentStatus;

// The same body is resent on every attempt; wallet-service deduplicates by reference
public record WebhookPayload(
        String reference,
        String walletDepositId,
        PaymentStatus status,
        long amount,
        String currency,
        String failureReason,
        Instant occurredAt
) {
}
