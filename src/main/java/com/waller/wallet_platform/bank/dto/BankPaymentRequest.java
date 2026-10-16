package com.waller.wallet_platform.bank.dto;

// Body of POST /payments on bank-sim
public record BankPaymentRequest(
        long amount,
        String currency,
        // Echoed back in the webhook, so the deposit can be found without a lookup by reference
        String walletDepositId
) {
}
