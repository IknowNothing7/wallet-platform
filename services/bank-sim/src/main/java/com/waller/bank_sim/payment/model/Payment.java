package com.waller.bank_sim.payment.model;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

// In-memory only: everything is lost on restart, which is fine for a simulator
@Getter
@Setter
public class Payment {

    private final String reference;
    private final String idempotencyKey;
    private final long amount;
    private final String currency;
    private final String walletDepositId;
    private final Instant createdAt = Instant.now();

    private volatile PaymentStatus status = PaymentStatus.PENDING;
    private volatile String failureReason;
    private volatile int webhookAttempts;
    private volatile Instant webhookDeliveredAt;

    public Payment(String reference, String idempotencyKey, long amount, String currency, String walletDepositId) {
        this.reference = reference;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.currency = currency;
        this.walletDepositId = walletDepositId;
    }

    /** Gives a PENDING payment its final status; false if it already has one, e.g. it was cancelled first. */
    public synchronized boolean settle(PaymentStatus finalStatus, String reason) {
        if (status != PaymentStatus.PENDING) {
            return false;
        }
        // Reason first: readers check status, so they never see a final status without its reason
        failureReason = reason;
        status = finalStatus;
        return true;
    }
}
