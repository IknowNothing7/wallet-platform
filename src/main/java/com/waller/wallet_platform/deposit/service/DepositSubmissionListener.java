package com.waller.wallet_platform.deposit.service;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import com.waller.wallet_platform.config.AsyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends a new deposit to the bank once createDeposit has committed, so the bank call holds no transaction and the
 * webhook can't arrive before the deposit row is visible. Asynchronous: POST /api/deposits returns as soon as the
 * deposit is stored, without waiting for the bank (up to bank.request-timeout, more under bank-sim's latency
 * scenario). The client learns the result from GET /api/deposits/{id} or the DepositSucceeded/Failed events.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DepositSubmissionListener {

    private final DepositProcessingService depositProcessingService;

    // On its own thread, so no transaction is attached and submitToBank's own short transactions really commit
    @Async(AsyncConfig.BANK_SUBMISSION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDepositCreated(DepositCreatedEvent event) {
        try {
            depositProcessingService.submitToBank(event.depositId());
        } catch (RuntimeException e) {
            // The deposit is already committed as PENDING; reconciliation resubmits it with the same idempotency key
            log.warn("Submitting deposit {} to the bank failed, reconciliation will retry: {}",
                    event.depositId(), e.getMessage());
        }
    }
}
