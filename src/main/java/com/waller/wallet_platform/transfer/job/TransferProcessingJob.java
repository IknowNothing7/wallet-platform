package com.waller.wallet_platform.transfer.job;

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
import com.waller.wallet_platform.transfer.entity.TransferStatus;
import com.waller.wallet_platform.transfer.repository.TransferRepository;
import com.waller.wallet_platform.transfer.service.TransferProcessingService;

/**
 * Books PENDING transfers. Processing is asynchronous: POST /transfer/create only stores the transfer, this job
 * is the only caller of process(), and the client polls GET /transfer/{id} for the result.
 */
@Component
@Slf4j
public class TransferProcessingJob {

    private final TransferRepository transferRepository;
    private final TransferProcessingService transferProcessingService;
    private final Duration pendingFor;
    private final Duration staleAfter;
    private final int batchSize;
    // Set at the end of each run and read on scrape, so scraping never queries the database
    private final AtomicLong stalePending = new AtomicLong();
    private final AtomicLong oldestStaleSeconds = new AtomicLong();

    public TransferProcessingJob(
            TransferRepository transferRepository,
            TransferProcessingService transferProcessingService,
            @Value("${transfer.processing.pending-for:PT0S}") Duration pendingFor,
            @Value("${transfer.processing.stale-after:PT1M}") Duration staleAfter,
            @Value("${transfer.processing.batch-size:100}") int batchSize,
            MeterRegistry meterRegistry) {
        this.transferRepository = transferRepository;
        this.transferProcessingService = transferProcessingService;
        this.pendingFor = pendingFor;
        this.staleAfter = staleAfter;
        this.batchSize = batchSize;
        Gauge.builder("wallet.transfer.stale.pending", stalePending, AtomicLong::get)
                .description("Transfers still PENDING stale-after past creation, after the last processing run")
                .register(meterRegistry);
        Gauge.builder("wallet.transfer.stale.pending.oldest", oldestStaleSeconds, AtomicLong::get)
                .description("Age of the oldest stale PENDING transfer, 0 when there is none")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    // Ids only, then one transaction per transfer: a failure rolls back that transfer, not the whole batch
    @Scheduled(fixedDelayString = "${transfer.processing.poll-interval:PT5S}")
    @SchedulerLock(name = "transferProcessing", lockAtMostFor = "PT5M")
    public void processPending() {
        Instant now = Instant.now();
        List<Long> ids = transferRepository.findDueIds(
                TransferStatus.PENDING, now.minus(pendingFor), now, PageRequest.of(0, batchSize));
        for (Long id : ids) {
            try {
                transferProcessingService.process(id);
            } catch (RuntimeException e) {
                // Not a business rejection (those end as FAILED); e.g. a lock timeout. Retried with backoff.
                recordFailedAttempt(id, e);
            }
        }
        updateStaleMetrics();
    }

    private void recordFailedAttempt(Long id, RuntimeException cause) {
        try {
            transferProcessingService.recordFailedAttempt(id, String.valueOf(cause.getMessage()));
        } catch (RuntimeException e) {
            // e.g. the database is unreachable; the transfer is still due, so the next run tries it again
            log.warn("Recording the failed attempt of transfer {} failed: {} (processing error: {})",
                    id, e.getMessage(), cause.getMessage());
        }
    }

    private void updateStaleMetrics() {
        Instant now = Instant.now();
        Instant before = now.minus(staleAfter);
        stalePending.set(transferRepository.countByStatusAndCreatedAtBefore(TransferStatus.PENDING, before));
        Instant oldest = transferRepository.findOldestCreatedAt(TransferStatus.PENDING, before);
        oldestStaleSeconds.set(oldest == null ? 0 : Duration.between(oldest, now).toSeconds());
    }
}
