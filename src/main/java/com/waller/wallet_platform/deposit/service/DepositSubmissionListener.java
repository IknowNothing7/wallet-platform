package com.waller.wallet_platform.deposit.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends a new deposit to the bank once createDeposit has committed, so the bank call holds no transaction
 * and the webhook can't arrive before the deposit row is visible.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DepositSubmissionListener {

    private final DepositProcessingService depositProcessingService;

    // NOT_SUPPORTED detaches the finished transaction, so submitToBank's own short transactions really commit
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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
