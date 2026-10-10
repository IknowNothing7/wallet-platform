package com.waller.wallet_platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.waller.wallet_platform.model.entites.OutboxEvent;
import com.waller.wallet_platform.model.enums.OutboxStatus;
import com.waller.wallet_platform.repositories.OutboxRepository;

class OutboxPublisherTest {

    private static final int MAX_ATTEMPTS = 3;

    private OutboxRepository repository;
    private KafkaTemplate<String, String> kafkaTemplate;
    private OutboxPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(OutboxRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(
                invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        publisher = new OutboxPublisher(repository, kafkaTemplate, transactionTemplate,
                "wallet.", 100, MAX_ATTEMPTS, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMinutes(5));
    }

    @Test
    void publishesToAggregateTopicKeyedByAggregateIdAndMarksPublished() {
        OutboxEvent event = event(1L);
         event.setLastError("earlier failure");
        when(repository.lockPendingBatch(anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(succeeded());

        publisher.publishPending();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
                assertThat(event.getLastError()).isNull();
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>argThat(record ->
                record.topic().equals("wallet.transfer")
                        && record.key().equals("42")
                        && record.value().equals("{\"amount\":100}")
                        && new String(record.headers().lastHeader("event-id").value()).equals("1")));
    }

    @Test
    void brokerUnavailableStopsBatchWithoutCountingAttemptAndPausesPublisher() {
        OutboxEvent first = event(1L);
        OutboxEvent second = event(2L);
        when(repository.lockPendingBatch(anyInt())).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failed(new TimeoutException("no broker")));

        publisher.publishPending();

        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(first.getAttempts()).isZero();
        assertThat(first.getLastError()).contains("no broker");
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PENDING);
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));

        // Backing off: the next poll doesn't touch the database
        publisher.publishPending();
        verify(repository, times(1)).lockPendingBatch(anyInt());
    }

    @Test
    void permanentFailureMarksEventFailedAndContinuesWithNext() {
        OutboxEvent tooLarge = event(1L);
        OutboxEvent next = event(2L);
        when(repository.lockPendingBatch(anyInt())).thenReturn(List.of(tooLarge, next));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed(new RecordTooLargeException("too big")))
                .thenReturn(succeeded());

        publisher.publishPending();

        assertThat(tooLarge.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(next.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void otherFailureCountsAttemptAndStopsBatchToKeepOrder() {
        OutboxEvent first = event(1L);
        OutboxEvent second = event(2L);
        when(repository.lockPendingBatch(anyInt())).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failed(new IllegalStateException("boom")));

        publisher.publishPending();

        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(second.getAttempts()).isZero();
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void otherFailureOnLastAttemptMarksFailedAndContinues() {
        OutboxEvent exhausted = event(1L);
        exhausted.setAttempts(MAX_ATTEMPTS - 1);
        OutboxEvent next = event(2L);
        when(repository.lockPendingBatch(anyInt())).thenReturn(List.of(exhausted, next));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed(new IllegalStateException("boom")))
                .thenReturn(succeeded());

        publisher.publishPending();

        assertThat(exhausted.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(exhausted.getAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(next.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void databaseErrorPausesPublisher() {
        when(repository.lockPendingBatch(anyInt())).thenThrow(new IllegalStateException("db down"));

        publisher.publishPending();
        publisher.publishPending();

        verify(repository, times(1)).lockPendingBatch(anyInt());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    private static OutboxEvent event(Long id) {
        OutboxEvent event = new OutboxEvent();
        event.setId(id);
        event.setCreatedAt(Instant.parse("2026-10-09T10:00:00Z"));
        event.setAggregateType("transfer");
        event.setAggregateId(42L);
        event.setEventType("transfer.completed");
        event.setPayload(JsonNodeFactory.instance.objectNode().put("amount", 100));
        return event;
    }

    private static CompletableFuture<SendResult<String, String>> succeeded() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, String>> failed(Throwable cause) {
        return CompletableFuture.failedFuture(cause);
    }

}
