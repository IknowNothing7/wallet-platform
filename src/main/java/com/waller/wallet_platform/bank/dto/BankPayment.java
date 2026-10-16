package com.waller.wallet_platform.bank.dto;

import java.time.Instant;

// Response of POST /payments and GET /payments/{reference}; unknown fields are ignored
public record BankPayment(
        String reference,
        long amount,
        String currency,
        String walletDepositId,
        BankPaymentStatus status,
        String failureReason,
        Instant createdAt
) {
}
