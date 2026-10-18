package com.waller.wallet_platform.bank.webhook;

import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

// A signed webhook body that could not be read, kept for manual review instead of only being logged
@Entity
@Table(name = "bank_webhook_dead_letter")
@NoArgsConstructor
@Getter
public class BankWebhookDeadLetter {

    private static final int MAX_REASON_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(nullable = false, length = MAX_REASON_LENGTH)
    private String reason;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    public BankWebhookDeadLetter(String reason, String body) {
        this.reason = reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
        this.body = body;
    }
}
