package com.waller.bank_sim.payment.model;

public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    // Cancelled by wallet-service before it settled; no webhook is sent for it
    CANCELLED
}
