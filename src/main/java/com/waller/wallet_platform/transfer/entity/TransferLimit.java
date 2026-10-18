package com.waller.wallet_platform.transfer.entity;

import com.waller.wallet_platform.account.entity.Account;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Either a currency default (currency set) or a per-account override (account set), never both.
// Amounts are minor units; null means no limit of that kind.
@Entity
@Table(name = "transfer_limit")
@NoArgsConstructor
@Getter
@Setter
public class TransferLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3)
    private String currency;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "per_transfer_max")
    private Long perTransferMax;

    @Column(name = "daily_max")
    private Long dailyMax;
}
