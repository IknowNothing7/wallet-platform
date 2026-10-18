package com.waller.notification_service.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.waller.notification_service.notification.entity.Notification;

import jakarta.persistence.EntityManager;

// Against the real Flyway schema in Postgres; no Kafka involved
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NotificationRepositoryTest {

    private static final long ALICE = 1L;
    private static final long BOB = 2L;
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final AtomicLong EVENT_IDS = new AtomicLong(5000);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgresql = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void markAllRead_marksOnlyThatUsersUnreadRowsAndReturnsTheCount() {
        Instant earlierRead = Instant.parse("2025-12-31T12:00:00Z");
        Notification aliceUnread1 = save(ALICE, 1, null);
        Notification aliceUnread2 = save(ALICE, 2, null);
        Notification aliceRead = save(ALICE, 3, earlierRead);
        Notification bobUnread = save(BOB, 4, null);
        Instant now = Instant.parse("2026-02-01T00:00:00Z");

        int updated = notificationRepository.markAllRead(ALICE, now);
        entityManager.flush();
        entityManager.clear();

        assertThat(updated).isEqualTo(2);
        assertThat(reload(aliceUnread1).getReadAt()).isEqualTo(now);
        assertThat(reload(aliceUnread2).getReadAt()).isEqualTo(now);
        // Already-read rows keep their original read time
        assertThat(reload(aliceRead).getReadAt()).isEqualTo(earlierRead);
        // Another user's unread rows are untouched
        assertThat(reload(bobUnread).getReadAt()).isNull();
    }

    @Test
    void markAllRead_nothingUnread_returnsZero() {
        save(ALICE, 1, BASE);

        assertThat(notificationRepository.markAllRead(ALICE, Instant.now())).isZero();
        assertThat(notificationRepository.markAllRead(999L, Instant.now())).isZero();
    }

    @Test
    void countUnread_countsOnlyThatUsersUnreadRows() {
        save(ALICE, 1, null);
        save(ALICE, 2, null);
        save(ALICE, 3, BASE);
        save(BOB, 4, null);

        assertThat(notificationRepository.countByUserIdAndReadAtIsNull(ALICE)).isEqualTo(2);
        assertThat(notificationRepository.countByUserIdAndReadAtIsNull(BOB)).isEqualTo(1);
        assertThat(notificationRepository.countByUserIdAndReadAtIsNull(999L)).isZero();
    }

    @Test
    void countUnread_dropsAfterMarkAllRead() {
        save(ALICE, 1, null);
        save(ALICE, 2, null);

        notificationRepository.markAllRead(ALICE, Instant.now());
        entityManager.clear();

        assertThat(notificationRepository.countByUserIdAndReadAtIsNull(ALICE)).isZero();
    }

    @Test
    void list_isNewestFirstAndScopedToTheUser() {
        // Inserted out of order, so the order comes from created_at and not from the id
        Notification middle = save(ALICE, 2, null);
        Notification oldest = save(ALICE, 1, null);
        Notification newest = save(ALICE, 3, null);
        save(BOB, 4, null);

        Page<Notification> page = notificationRepository.findByUserIdOrderByCreatedAtDesc(ALICE, PageRequest.of(0, 10));

        assertThat(ids(page.getContent())).containsExactly(newest.getId(), middle.getId(), oldest.getId());
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void list_pagesThroughNewestFirst() {
        Notification n1 = save(ALICE, 1, null);
        Notification n2 = save(ALICE, 2, null);
        Notification n3 = save(ALICE, 3, null);
        Notification n4 = save(ALICE, 4, null);
        Notification n5 = save(ALICE, 5, null);
        save(BOB, 6, null);

        Page<Notification> first = notificationRepository.findByUserIdOrderByCreatedAtDesc(ALICE, PageRequest.of(0, 2));
        Page<Notification> second = notificationRepository.findByUserIdOrderByCreatedAtDesc(ALICE, PageRequest.of(1, 2));
        Page<Notification> last = notificationRepository.findByUserIdOrderByCreatedAtDesc(ALICE, PageRequest.of(2, 2));

        assertThat(ids(first.getContent())).containsExactly(n5.getId(), n4.getId());
        assertThat(ids(second.getContent())).containsExactly(n3.getId(), n2.getId());
        assertThat(ids(last.getContent())).containsExactly(n1.getId());
        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(last.hasNext()).isFalse();
    }

    @Test
    void findByIdAndUserId_doesNotFindAnotherUsersNotification() {
        Notification bobs = save(BOB, 1, null);

        assertThat(notificationRepository.findByIdAndUserId(bobs.getId(), BOB)).isPresent();
        assertThat(notificationRepository.findByIdAndUserId(bobs.getId(), ALICE)).isEmpty();
    }

    // createdAt comes from @CreationTimestamp; it is pinned to BASE + minuteOffset so ordering is deterministic
    private Notification save(long userId, int minuteOffset, Instant readAt) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setEventId(EVENT_IDS.incrementAndGet());
        notification.setEventType("TransferCompleted");
        notification.setTitle("title " + minuteOffset);
        notification.setBody("body " + minuteOffset);
        notification.setReadAt(readAt);
        Notification saved = notificationRepository.saveAndFlush(notification);
        entityManager.createNativeQuery("update notification.notification set created_at = :createdAt where id = :id")
                .setParameter("createdAt", Timestamp.from(BASE.plusSeconds(60L * minuteOffset)))
                .setParameter("id", saved.getId())
                .executeUpdate();
        entityManager.clear();
        return saved;
    }

    private Notification reload(Notification notification) {
        return notificationRepository.findById(notification.getId()).orElseThrow();
    }

    private static List<Long> ids(List<Notification> notifications) {
        return notifications.stream().map(Notification::getId).toList();
    }
}
