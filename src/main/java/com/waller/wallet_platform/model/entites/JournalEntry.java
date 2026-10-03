package com.waller.wallet_platform.model.entites;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import com.waller.wallet_platform.model.enums.JournalEntryType;

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
@Table(name = "journal_entry")
@NoArgsConstructor
@Getter
@Setter
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private JournalEntryType entryType;

    private String description;

    @Column(name = "reference_type")
    private String referenceType;
    @Column(name = "reference_id")
    private Long referenceId;

}
