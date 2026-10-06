package com.waller.wallet_platform.model.dto;

import java.io.Serializable;
import java.time.Instant;

import com.waller.wallet_platform.model.enums.DepositStatus;

import lombok.Builder;

@Builder
public record DepositDto(
        int id,
        Instant createdAt,
        Instant updatetAt,
        int accountId,
        int amount,
        String currency,
        String gatewayString,
        String gatewayReference,
        DepositStatus status,
        String failureReason,
        String expiresAt

) implements  Serializable{

}
