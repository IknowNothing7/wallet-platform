package com.waller.bank_sim.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PaymentRequest(
        @Positive long amount,
        @NotBlank @Size(min = 3, max = 3) String currency,
        // Echoed back in the webhook so wallet-service can find its deposit without a lookup by reference
        @NotBlank String walletDepositId
) {
}
