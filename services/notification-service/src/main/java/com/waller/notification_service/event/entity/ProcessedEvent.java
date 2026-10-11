package com.waller.notification_service.event.entity;

import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "processed_event")
@NoArgsConstructor
@Getter
@Setter
public class ProcessedEvent {

    // The wallet outbox row id, sent in the "event-id" header
    @Id
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @CreationTimestamp
    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    public ProcessedEvent(Long eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
    }
}
