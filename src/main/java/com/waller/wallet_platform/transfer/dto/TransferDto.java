package com.waller.wallet_platform.transfer.dto;

import java.io.Serializable;
import java.time.Instant;
import lombok.Builder;
import com.waller.wallet_platform.transfer.entity.TransferStatus;

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
