package com.waller.wallet_platform.model.dto;

import java.io.Serializable;
import java.time.Instant;

import com.waller.wallet_platform.model.enums.DepositStatus;

import lombok.Builder;

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
