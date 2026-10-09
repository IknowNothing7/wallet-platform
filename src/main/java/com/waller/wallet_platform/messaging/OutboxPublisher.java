package com.waller.wallet_platform.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.waller.wallet_platform.model.entites.OutboxEvent;
import com.waller.wallet_platform.model.enums.OutboxStatus;
import com.waller.wallet_platform.repositories.OutboxRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Sends PENDING outbox events to Kafka, oldest first, and marks them PUBLISHED.
 *
 * <p>Delivery is at-least-once: if the database commit fails after Kafka acknowledged a send, the event is
 * sent again on the next poll, so consumers should de-duplicate on the {@code event-id} header.
 * Events go to topic {@code <topic-prefix><aggregateType>} keyed by aggregate id, so one aggregate's events
 * stay in order on one partition (with a single publishing instance; several instances stay safe but may
 * interleave an aggregate's events across batches).
 *
 * <p>Failures are handled by kind:
 * <ul>
 * <li>broker unreachable / timeouts: the batch stops, the publisher backs off, and the event's attempts are
 * not counted, so an outage doesn't use up events' attempts;</li>
 * <li>errors that can never succeed (record too large, invalid topic, serialization): the event is marked
 * FAILED at once and the batch continues;</li>
 * <li>anything else: the attempt is counted and the batch stops to keep order; after max-attempts the event
 * is marked FAILED. Admins can retry FAILED events via POST /outbox/{id}/retry.</li>
 * </ul>
 */
@Component
@Slf4j
public class OutboxPublisher {

    private static final int MAX_ERROR_LENGTH = 2000;

    private enum FailureKind { BROKER_UNAVAILABLE, PERMANENT, OTHER }

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String topicPrefix;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration sendTimeout;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    // Only touched by the scheduler thread: fixed-delay runs of one method never overlap
    private int consecutiveFailures;
    private Instant pausedUntil = Instant.MIN;

    public OutboxPublisher(
            OutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            TransactionTemplate transactionTemplate,
            @Value("${outbox.publisher.topic-prefix:wallet.}") String topicPrefix,
            @Value("${outbox.publisher.batch-size:100}") int batchSize,
            @Value("${outbox.publisher.max-attempts:10}") int maxAttempts,
            @Value("${outbox.publisher.send-timeout:PT35S}") Duration sendTimeout,
            @Value("${outbox.publisher.initial-backoff:PT1S}") Duration initialBackoff,
            @Value("${outbox.publisher.max-backoff:PT5M}") Duration maxBackoff) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.topicPrefix = topicPrefix;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.sendTimeout = sendTimeout;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
    }

    @Scheduled(fixedDelayString = "${outbox.publisher.poll-interval:PT1S}")
    public void publishPending() {
        if (Instant.now().isBefore(pausedUntil)) {
            return;
        }
        Boolean completed;
        try {
            // One transaction per batch: the row locks are held until the batch's results are committed
            completed = transactionTemplate.execute(status -> publishBatch());
        } catch (RuntimeException e) {
            // e.g. the database is unreachable; nothing was committed, so the events are picked up again later
            log.error("Outbox publishing batch failed", e);
            completed = false;
        }
        if (Boolean.TRUE.equals(completed)) {
            consecutiveFailures = 0;
        } else {
            backOff();
        }
    }

    /** Returns false when the batch stopped early on a failure that should pause the publisher. */
    private boolean publishBatch() {
        for (OutboxEvent event : outboxRepository.lockPendingBatch(batchSize)) {
            try {
                send(event);
                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());
                // An earlier failure no longer applies once the event is out
                event.setLastError(null);
                log.debug("Published outbox event {} ({})", event.getId(), event.getEventType());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                if (!handleFailure(event, e)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Records the failure on the event; returns whether the batch may continue with the next event. */
    private boolean handleFailure(OutboxEvent event, Exception e) {
        event.setLastError(describe(e));
        switch (classify(e)) {
            case BROKER_UNAVAILABLE -> {
                log.warn("Kafka unavailable while publishing outbox event {}: {}", event.getId(), describe(e));
                return false;
            }
            case PERMANENT -> {
                event.setAttempts(event.getAttempts() + 1);
                event.setStatus(OutboxStatus.FAILED);
                log.error("Outbox event {} can never be published, marked FAILED", event.getId(), e);
                return true;
            }
            default -> {
                event.setAttempts(event.getAttempts() + 1);
                if (event.getAttempts() >= maxAttempts) {
                    event.setStatus(OutboxStatus.FAILED);
                    log.error("Outbox event {} failed {} times, marked FAILED", event.getId(), event.getAttempts(), e);
                    return true;
                }
                log.warn("Publishing outbox event {} failed (attempt {}/{})",
                        event.getId(), event.getAttempts(), maxAttempts, e);
                return false;
            }
        }
    }

    private void send(OutboxEvent event) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topicPrefix + event.getAggregateType(),
                String.valueOf(event.getAggregateId()),
                event.getPayload().toString());
        addHeader(record, "event-id", String.valueOf(event.getId()));
        addHeader(record, "event-type", event.getEventType());
        addHeader(record, "aggregate-type", event.getAggregateType());
        addHeader(record, "aggregate-id", String.valueOf(event.getAggregateId()));
        addHeader(record, "created-at", String.valueOf(event.getCreatedAt()));
        kafkaTemplate.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void addHeader(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static FailureKind classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof RecordTooLargeException || t instanceof InvalidTopicException
                    || t instanceof SerializationException) {
                return FailureKind.PERMANENT;
            }
            if (t instanceof RetriableException || t instanceof TimeoutException) {
                return FailureKind.BROKER_UNAVAILABLE;
            }
        }
        return FailureKind.OTHER;
    }

    private void backOff() {
        consecutiveFailures++;
        long multiplier = 1L << Math.min(consecutiveFailures - 1, 20);
        Duration delay = initialBackoff.multipliedBy(multiplier);
        if (delay.compareTo(maxBackoff) > 0) {
            delay = maxBackoff;
        }
        pausedUntil = Instant.now().plus(delay);
        log.info("Outbox publisher paused for {} after {} consecutive failed batch(es)", delay, consecutiveFailures);
    }

    // The deepest cause usually names the real problem (KafkaProducerException wraps the client's exception)
    private static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getClass().getSimpleName() + ": " + root.getMessage();
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }

}
