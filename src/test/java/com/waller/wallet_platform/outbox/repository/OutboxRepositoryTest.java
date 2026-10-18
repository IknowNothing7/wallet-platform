package com.waller.wallet_platform.outbox.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.waller.wallet_platform.outbox.entity.OutboxEvent;
import com.waller.wallet_platform.outbox.entity.OutboxStatus;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.support.IntegrationTest;

// The native queries the publisher and the retry endpoint rely on, run against real Postgres
class OutboxRepositoryTest extends IntegrationTest {

    // The rows here are made up to test the queries, not real events
    @Override
    protected boolean checksEventContracts() {
        return false;
    }

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxEventService outboxEventService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void lockPendingBatch_returnsOnlyPendingRowsOldestFirst() {
        long newer = insert(1, "2026-10-09T10:02:00Z", OutboxStatus.PENDING);
        long published = insert(2, "2026-10-09T09:00:00Z", OutboxStatus.PUBLISHED);
        long failed = insert(3, "2026-10-09T09:30:00Z", OutboxStatus.FAILED);
        long oldest = insert(4, "2026-10-09T10:00:00Z", OutboxStatus.PENDING);

        List<Long> ids = inTransaction(() -> ids(outboxRepository.lockPendingBatch(10)));

        assertThat(ids).containsExactly(oldest, newer).doesNotContain(published, failed);
    }

    @Test
    void lockPendingBatch_respectsLimit() {
        long oldest = insert(1, "2026-10-09T10:00:00Z", OutboxStatus.PENDING);
        insert(2, "2026-10-09T10:01:00Z", OutboxStatus.PENDING);

        assertThat(inTransaction(() -> ids(outboxRepository.lockPendingBatch(1)))).containsExactly(oldest);
    }

    @Test
    void lockPendingBatch_skipsRowsLockedByAnotherTransaction() {
        long first = insert(1, "2026-10-09T10:00:00Z", OutboxStatus.PENDING);
        long second = insert(2, "2026-10-09T10:01:00Z", OutboxStatus.PENDING);
        TransactionTemplate other = new TransactionTemplate(transactionManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        List<Long> seenByOther = inTransaction(() -> {
            assertThat(ids(outboxRepository.lockPendingBatch(1))).containsExactly(first);
            return other.execute(status -> ids(outboxRepository.lockPendingBatch(10)));
        });

        assertThat(seenByOther).containsExactly(second);
    }

    @Test
    void resetFailedToPending_resetsFailedRowAndKeepsLastError() {
        long failed = insert(1, "2026-10-09T10:00:00Z", OutboxStatus.FAILED);
        jdbcTemplate.update("UPDATE outbox_event SET attempts = 10, last_error = 'boom' WHERE id = ?", failed);

        int updated = inTransaction(() -> outboxRepository.resetFailedToPending(failed));

        assertThat(updated).isEqualTo(1);
        Map<String, Object> row = row(failed);
        assertThat(row.get("status")).hasToString("PENDING");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("last_error")).isEqualTo("boom");
    }

    @Test
    void resetFailedToPending_leavesOtherStatusesAlone() {
        long pending = insert(1, "2026-10-09T10:00:00Z", OutboxStatus.PENDING);
        long published = insert(2, "2026-10-09T10:01:00Z", OutboxStatus.PUBLISHED);
        jdbcTemplate.update("UPDATE outbox_event SET attempts = 3 WHERE id IN (?, ?)", pending, published);

        assertThat(inTransaction(() -> outboxRepository.resetFailedToPending(pending))).isZero();
        assertThat(inTransaction(() -> outboxRepository.resetFailedToPending(published))).isZero();
        assertThat(inTransaction(() -> outboxRepository.resetFailedToPending(999L))).isZero();

        assertThat(row(pending).get("attempts")).isEqualTo(3);
        assertThat(row(published).get("status")).hasToString("PUBLISHED");
        assertThat(row(published).get("attempts")).isEqualTo(3);
    }

    @Test
    void record_outsideTransaction_isRejected() {
        assertThatThrownBy(() -> outboxEventService.record("transfer", 1L, "transfer.completed", Map.of("amount", 100)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void record_insideTransaction_storesPendingEvent() {
        inTransaction(() -> {
            outboxEventService.record("transfer", 1L, "transfer.completed", Map.of("amount", 100));
            return null;
        });

        OutboxEvent event = outboxRepository.findAll().getFirst();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getEventType()).isEqualTo("transfer.completed");
        assertThat(event.getPayload().get("amount").asInt()).isEqualTo(100);
    }

    private long insert(long aggregateId, String createdAt, OutboxStatus status) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO outbox_event (created_at, aggregate_type, aggregate_id, event_type, payload, status)
                VALUES (?, 'transfer', ?, 'transfer.completed', '{}'::jsonb, ?::outbox_status)
                RETURNING id""",
                Long.class, Timestamp.from(Instant.parse(createdAt)), aggregateId, status.name());
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("SELECT status, attempts, last_error FROM outbox_event WHERE id = ?", id);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private static List<Long> ids(List<OutboxEvent> events) {
        return events.stream().map(OutboxEvent::getId).toList();
    }
}
