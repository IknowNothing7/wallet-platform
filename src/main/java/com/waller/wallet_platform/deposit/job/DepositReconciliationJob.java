package com.waller.wallet_platform.deposit.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
    // Set at the end of each run and read on scrape, so scraping never queries the database
    private final AtomicLong stalePending = new AtomicLong();
    private final AtomicLong oldestStaleSeconds = new AtomicLong();

    public DepositReconciliationJob(
            DepositRepository depositRepository,
            DepositProcessingService depositProcessingService,
            BankClient bankClient,
            @Value("${deposit.reconciliation.stale-after:PT1M}") Duration staleAfter,
            @Value("${deposit.reconciliation.batch-size:50}") int batchSize,
            MeterRegistry meterRegistry) {
        this.depositRepository = depositRepository;
        this.depositProcessingService = depositProcessingService;
        this.bankClient = bankClient;
        this.staleAfter = staleAfter;
        this.batchSize = batchSize;
        Gauge.builder("wallet.deposit.stale.pending", stalePending, AtomicLong::get)
                .description("PENDING deposits not updated within stale-after, after the last reconciliation run")
                .register(meterRegistry);
        Gauge.builder("wallet.deposit.stale.pending.oldest", oldestStaleSeconds, AtomicLong::get)
                .description("Age of the oldest stale PENDING deposit, 0 when there is none")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${deposit.reconciliation.poll-interval:PT30S}")
    @SchedulerLock(name = "depositReconciliation", lockAtMostFor = "PT5M")
    public void reconcile() {
        List<Deposit> stale = depositRepository.findStale(
                DepositStatus.PENDING, Instant.now().minus(staleAfter), PageRequest.of(0, batchSize));
        for (Deposit deposit : stale) {
            try {
                reconcile(deposit);
            } catch (RuntimeException e) {
                log.warn("Reconciliation of deposit {} failed: {}", deposit.getId(), e.getMessage());
            }
        }
        updateStaleMetrics();
    }

    private void updateStaleMetrics() {
        Instant now = Instant.now();
        Instant before = now.minus(staleAfter);
        stalePending.set(depositRepository.countByStatusAndUpdatedAtBefore(DepositStatus.PENDING, before));
        Instant oldest = depositRepository.findOldestUpdatedAt(DepositStatus.PENDING, before);
        oldestStaleSeconds.set(oldest == null ? 0 : Duration.between(oldest, now).toSeconds());
    }

    private void reconcile(Deposit deposit) {
        if (deposit.getExpiresAt() != null && !deposit.getExpiresAt().isAfter(Instant.now())) {
            depositProcessingService.expire(deposit.getId());
            return;
        }
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
