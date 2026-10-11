package com.waller.wallet_platform.deposit.dto;

import java.io.Serializable;
import java.time.Instant;
import lombok.Builder;
import com.waller.wallet_platform.deposit.entity.DepositStatus;

@Builder
public record DepositDto(
        Long id,
        Instant createdAt,
        Instant updatedAt,
        Long accountId,
        Long amount,
        String currency,
        String gateway,
        String gatewayReference,
        DepositStatus status,
        String failureReason,
        Instant expiresAt

) implements Serializable {

}
