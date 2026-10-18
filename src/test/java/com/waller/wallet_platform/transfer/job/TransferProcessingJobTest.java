package com.waller.wallet_platform.transfer.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.waller.wallet_platform.transfer.entity.TransferStatus;
import com.waller.wallet_platform.transfer.repository.TransferRepository;
import com.waller.wallet_platform.transfer.service.TransferProcessingService;

class TransferProcessingJobTest {

    private TransferRepository repository;
    private TransferProcessingService service;
    private SimpleMeterRegistry meterRegistry;
    private TransferProcessingJob job;

    @BeforeEach
    void setUp() {
        repository = mock(TransferRepository.class);
        service = mock(TransferProcessingService.class);
        meterRegistry = new SimpleMeterRegistry();
        job = new TransferProcessingJob(repository, service,
                Duration.ofSeconds(10), Duration.ofMinutes(1), 100, meterRegistry);
    }

    @Test
    void processesDueTransfers() {
        when(repository.findDueIds(eq(TransferStatus.PENDING), any(), any(), any())).thenReturn(List.of(1L, 2L));

        job.processPending();

        verify(service).process(1L);
        verify(service).process(2L);
        verify(service, never()).recordFailedAttempt(any(), anyString());
    }

    @Test
    void failedProcessingIsRecordedAndDoesNotStopTheBatch() {
        when(repository.findDueIds(eq(TransferStatus.PENDING), any(), any(), any())).thenReturn(List.of(1L, 2L));
        when(service.process(1L)).thenThrow(new IllegalStateException("lock timeout"));

        job.processPending();

        verify(service).recordFailedAttempt(1L, "lock timeout");
        verify(service).process(2L);
    }

    @Test
    void failureToRecordAttemptDoesNotStopTheBatch() {
        when(repository.findDueIds(eq(TransferStatus.PENDING), any(), any(), any())).thenReturn(List.of(1L, 2L));
        when(service.process(1L)).thenThrow(new IllegalStateException("lock timeout"));
        when(service.recordFailedAttempt(1L, "lock timeout")).thenThrow(new IllegalStateException("db down"));

        job.processPending();

        verify(service).process(2L);
    }

    @Test
    void publishesStaleMetrics() {
        when(repository.findDueIds(eq(TransferStatus.PENDING), any(), any(), any())).thenReturn(List.of());
        when(repository.countByStatusAndCreatedAtBefore(eq(TransferStatus.PENDING), any())).thenReturn(3L);
        when(repository.findOldestCreatedAt(eq(TransferStatus.PENDING), any()))
                .thenReturn(Instant.now().minusSeconds(120));

        job.processPending();

        assertThat(meterRegistry.get("wallet.transfer.stale.pending").gauge().value()).isEqualTo(3);
        assertThat(meterRegistry.get("wallet.transfer.stale.pending.oldest").gauge().value()).isBetween(119.0, 125.0);
    }
}
