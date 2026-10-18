package com.waller.wallet_platform.outbox.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.waller.wallet_platform.outbox.repository.OutboxRepository;
import com.waller.wallet_platform.outbox.entity.OutboxEvent;
import com.waller.wallet_platform.outbox.entity.OutboxStatus;
import static com.waller.wallet_platform.support.TestData.outboxEvent;

class OutboxPublisherTest {

    private static final int MAX_ATTEMPTS = 3;

    private OutboxRepository repository;
    private KafkaTemplate<String, String> kafkaTemplate;
    private TransactionTemplate transactionTemplate;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(
                invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        publisher = publisher(Duration.ofSeconds(1), Duration.ofMinutes(5));
    }

    private OutboxPublisher publisher(Duration initialBackoff, Duration maxBackoff) {
        return new OutboxPublisher(repository, kafkaTemplate, transactionTemplate,
                "wallet.", 100, MAX_ATTEMPTS, Duration.ofSeconds(1), initialBackoff, maxBackoff);
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

    @Test
    void publishingResumesAfterPauseAndSuccessfulBatchResetsBackoff() {
        publisher = publisher(Duration.ZERO, Duration.ofMinutes(5));
        OutboxEvent event = event(1L);
        when(repository.lockPendingBatch(anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed(new TimeoutException("no broker")))
                .thenReturn(failed(new TimeoutException("no broker")))
                .thenReturn(succeeded());

        publisher.publishPending();
        publisher.publishPending();
        assertThat(ReflectionTestUtils.getField(publisher, "consecutiveFailures")).isEqualTo(2);

        publisher.publishPending();

        verify(repository, times(3)).lockPendingBatch(anyInt());
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(ReflectionTestUtils.getField(publisher, "consecutiveFailures")).isEqualTo(0);
    }

    @Test
    void backoffDoublesAndStopsGrowingAtMaxBackoff() {
        publisher = publisher(Duration.ofSeconds(1), Duration.ofSeconds(4));
        when(repository.lockPendingBatch(anyInt())).thenThrow(new IllegalStateException("db down"));

        assertThat(pauseAfterFailedBatch()).isBetween(Duration.ofMillis(900), Duration.ofSeconds(1));
        assertThat(pauseAfterFailedBatch()).isBetween(Duration.ofMillis(1900), Duration.ofSeconds(2));
        assertThat(pauseAfterFailedBatch()).isBetween(Duration.ofMillis(3900), Duration.ofSeconds(4));
        assertThat(pauseAfterFailedBatch()).isBetween(Duration.ofMillis(3900), Duration.ofSeconds(4));
    }

    @Test
    void wrappedRetriableExceptionCountsAsBrokerUnavailable() {
        OutboxEvent first = event(1L);
        OutboxEvent second = event(2L);
        when(repository.lockPendingBatch(anyInt())).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed(new KafkaException("send failed", new NetworkException("connection lost"))));

        publisher.publishPending();

        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(first.getAttempts()).isZero();
        assertThat(first.getLastError()).isEqualTo("NetworkException: connection lost");
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));

        publisher.publishPending();
        verify(repository, times(1)).lockPendingBatch(anyInt());
    }

    @Test
    void lastErrorIsCutTo2000Characters() {
        OutboxEvent event = event(1L);
        when(repository.lockPendingBatch(anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed(new IllegalStateException("x".repeat(5000))));

        publisher.publishPending();

        assertThat(event.getLastError()).hasSize(2000).startsWith("IllegalStateException: xxx");
    }

    @Test
    @SuppressWarnings("unchecked")
    void setsAllHeaders() {
        when(repository.lockPendingBatch(anyInt())).thenReturn(List.of(event(1L)));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(succeeded());

        publisher.publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        Headers headers = captor.getValue().headers();
        assertThat(header(headers, "event-id")).isEqualTo("1");
        assertThat(header(headers, "event-type")).isEqualTo("transfer.completed");
        assertThat(header(headers, "aggregate-type")).isEqualTo("transfer");
        assertThat(header(headers, "aggregate-id")).isEqualTo("42");
        assertThat(header(headers, "created-at")).isEqualTo("2026-10-09T10:00:00Z");
        assertThat(headers.toArray()).hasSize(5);
    }

    private Duration pauseAfterFailedBatch() {
        ReflectionTestUtils.setField(publisher, "pausedUntil", Instant.MIN);
        publisher.publishPending();
        Instant pausedUntil = (Instant) ReflectionTestUtils.getField(publisher, "pausedUntil");
        return Duration.between(Instant.now(), pausedUntil);
    }

    private static String header(Headers headers, String name) {
        return new String(headers.lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static OutboxEvent event(Long id) {
        OutboxEvent event = outboxEvent("transfer", 42L, "transfer.completed");
        event.setId(id);
        event.setCreatedAt(Instant.parse("2026-10-09T10:00:00Z"));
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
