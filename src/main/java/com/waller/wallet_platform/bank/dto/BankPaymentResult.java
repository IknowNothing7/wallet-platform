package com.waller.wallet_platform.bank.dto;

import java.time.Instant;

// Webhook body, see contracts/webhooks/BankPaymentResult.v1.schema.json
public record BankPaymentResult(
        String reference,
        String walletDepositId,
        BankPaymentStatus status,
        long amount,
        String currency,
        String failureReason,
        // When this attempt was sent, not when the payment settled
        Instant occurredAt
) {
}
