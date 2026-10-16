package com.waller.wallet_platform.bank.dto;

// Mirrors bank-sim's PaymentStatus; only SUCCEEDED and FAILED ever arrive in a webhook
public enum BankPaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
