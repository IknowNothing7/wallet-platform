package com.waller.wallet_platform.deposit.service;

import com.waller.wallet_platform.bank.dto.BankPaymentResult;
import com.waller.wallet_platform.deposit.dto.DepositDto;

// Moves a deposit through the bank: PENDING -> SUCCEEDED | FAILED | EXPIRED
public interface DepositProcessingService {

    /** Sends a PENDING deposit to the bank. Safe to repeat: the idempotency key is derived from the deposit id. */
    DepositDto submitToBank(Long depositId);

    /**
     * Applies a final bank result, from the webhook or from reconciliation. Idempotent: results arrive
     * at least once, so a repeat of an already applied result is a no-op.
     */
    void applyResult(BankPaymentResult result);

    /** Marks a deposit that is still PENDING after its expiresAt as EXPIRED. */
    void expire(Long depositId);
}
