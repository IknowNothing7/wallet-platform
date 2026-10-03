package com.waller.wallet_platform.model.entites;

import java.io.Serializable;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.waller.wallet_platform.model.enums.PostingDirection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity 
@Table (name = "posting")
@NoArgsConstructor 
@Getter 
@Setter 
public class Posting  implements Serializable{

    @Id 
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "journal_entry_id", nullable = false, updatable = false)
    private Long journalEntryId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private PostingDirection direction;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private char currency;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
