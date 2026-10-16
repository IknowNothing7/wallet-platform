package com.waller.wallet_platform.transfer.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import com.waller.wallet_platform.transfer.entity.TransferStatus;
import com.waller.wallet_platform.transfer.repository.TransferRepository;
import com.waller.wallet_platform.transfer.service.TransferProcessingService;

/**
 * Books transfers that are still PENDING: the main path if processing is asynchronous, otherwise a safety net
 * for transfers whose synchronous processing never ran (e.g. a crash between create and process).
 */
@Component
@Slf4j
public class TransferProcessingJob {

    private final TransferRepository transferRepository;
    private final TransferProcessingService transferProcessingService;
    private final Duration pendingFor;
    private final int batchSize;

    public TransferProcessingJob(
            TransferRepository transferRepository,
            TransferProcessingService transferProcessingService,
            @Value("${transfer.processing.pending-for:PT10S}") Duration pendingFor,
            @Value("${transfer.processing.batch-size:100}") int batchSize) {
        this.transferRepository = transferRepository;
        this.transferProcessingService = transferProcessingService;
        this.pendingFor = pendingFor;
        this.batchSize = batchSize;
    }

    // Ids only, then one transaction per transfer: a failure rolls back that transfer, not the whole batch
    @Scheduled(fixedDelayString = "${transfer.processing.poll-interval:PT5S}")
    public void processPending() {
        // TODO: With several wallet-service instances every one runs this; add ShedLock or SKIP LOCKED claiming
        // TODO: Set pending-for to 0 if processing becomes asynchronous
        List<Long> ids = transferRepository.findIdsByStatusCreatedBefore(
                TransferStatus.PENDING, Instant.now().minus(pendingFor), PageRequest.of(0, batchSize));
        for (Long id : ids) {
            try {
                transferProcessingService.process(id);
            } catch (RuntimeException e) {
                // Not a business rejection (those end as FAILED); e.g. a lock timeout. Retried on the next run.
                // TODO: Count attempts and fail the transfer after N, so one broken row isn't retried forever
                log.warn("Processing of transfer {} failed: {}", id, e.getMessage());
            }
        }
        // TODO: Metrics: count of PENDING transfers and oldest age, for an alert
    }
}
