package com.waller.wallet_platform.deposit.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import com.waller.wallet_platform.bank.client.BankClient;
import com.waller.wallet_platform.bank.dto.BankPayment;
import com.waller.wallet_platform.bank.dto.BankPaymentResult;
import com.waller.wallet_platform.bank.dto.BankPaymentStatus;
import com.waller.wallet_platform.deposit.entity.Deposit;
import com.waller.wallet_platform.deposit.entity.DepositStatus;
import com.waller.wallet_platform.deposit.repository.DepositRepository;
import com.waller.wallet_platform.deposit.service.DepositProcessingService;

/**
 * Safety net for webhooks that never arrive (bank-sim's DROP scenario, or wallet-service down for longer than
 * bank-sim keeps retrying): asks the bank about deposits that have been PENDING for too long.
 */
@Component
@Slf4j
public class DepositReconciliationJob {

    private final DepositRepository depositRepository;
    private final DepositProcessingService depositProcessingService;
    private final BankClient bankClient;
    private final Duration staleAfter;
    private final int batchSize;

    public DepositReconciliationJob(
            DepositRepository depositRepository,
            DepositProcessingService depositProcessingService,
            BankClient bankClient,
            @Value("${deposit.reconciliation.stale-after:PT1M}") Duration staleAfter,
            @Value("${deposit.reconciliation.batch-size:50}") int batchSize) {
        this.depositRepository = depositRepository;
        this.depositProcessingService = depositProcessingService;
        this.bankClient = bankClient;
        this.staleAfter = staleAfter;
        this.batchSize = batchSize;
    }

    // No transaction around the run: each deposit is settled in its own, and the bank calls happen outside them
    @Scheduled(fixedDelayString = "${deposit.reconciliation.poll-interval:PT30S}")
    public void reconcile() {
        // TODO: With several wallet-service instances every one runs this; add ShedLock or SKIP LOCKED claiming
        List<Deposit> stale = depositRepository.findStale(
                DepositStatus.PENDING, Instant.now().minus(staleAfter), PageRequest.of(0, batchSize));
        for (Deposit deposit : stale) {
            try {
                reconcile(deposit);
            } catch (RuntimeException e) {
                // One bad deposit or a bank outage must not stop the rest of the batch
                log.warn("Reconciliation of deposit {} failed: {}", deposit.getId(), e.getMessage());
            }
        }
        // TODO: Metrics: count of stale PENDING deposits and oldest age, for an alert
    }

    private void reconcile(Deposit deposit) {
        if (deposit.getExpiresAt() != null && !deposit.getExpiresAt().isAfter(Instant.now())) {
            depositProcessingService.expire(deposit.getId());
            return;
        }
        // submitToBank never got a response (or never ran): resubmit, the same idempotency key means no double charge
        if (deposit.getGatewayReference() == null) {
            depositProcessingService.submitToBank(deposit.getId());
            log.info("Resubmitted deposit {} to the bank", deposit.getId());
            return;
        }
        BankPayment payment = bankClient.getPayment(deposit.getGatewayReference());
        if (payment.status() == BankPaymentStatus.PENDING) {
            return;
        }
        depositProcessingService.applyResult(new BankPaymentResult(
                payment.reference(),
                payment.walletDepositId(),
                payment.status(),
                payment.amount(),
                payment.currency(),
                payment.failureReason(),
                Instant.now()));
        log.info("Reconciled deposit {} as {}", deposit.getId(), payment.status());
    }
}
