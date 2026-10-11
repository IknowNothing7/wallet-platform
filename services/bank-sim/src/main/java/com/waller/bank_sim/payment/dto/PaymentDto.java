package com.waller.bank_sim.payment.dto;

import java.time.Instant;
import com.waller.bank_sim.payment.model.Payment;
import com.waller.bank_sim.payment.model.PaymentStatus;

public record PaymentDto(
        String reference,
        long amount,
        String currency,
        String walletDepositId,
        PaymentStatus status,
        String failureReason,
        int webhookAttempts,
        Instant webhookDeliveredAt,
        Instant createdAt
) {

    public static PaymentDto of(Payment payment) {
        return new PaymentDto(
                payment.getReference(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getWalletDepositId(),
                payment.getStatus(),
                payment.getFailureReason(),
                payment.getWebhookAttempts(),
                payment.getWebhookDeliveredAt(),
                payment.getCreatedAt());
    }
}
