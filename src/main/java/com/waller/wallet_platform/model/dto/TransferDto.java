package com.waller.wallet_platform.model.dto;

import java.io.Serializable;
import java.time.Instant;

import com.waller.wallet_platform.model.enums.TransferStatus;

import lombok.Builder;

@Builder
public record TransferDto(
        Long id,
        Instant createdAt,
        Instant updatedAt,
        Long fromAccountId,
        Long toAccountId,
        Long amount,
        String currency,
        TransferStatus status,
        String failureReason

) implements Serializable {

}
