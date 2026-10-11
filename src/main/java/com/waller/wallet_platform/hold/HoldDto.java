package com.waller.wallet_platform.hold;

import java.io.Serializable;
import java.time.Instant;
import lombok.Builder;

@Builder
public record HoldDto(
        Long id,
        Instant createdAt,
        Instant updatedAt,
        Long accountId,
        Long amount,
        String currency,
        HoldStatus status,
        Instant expiresAt,
        Long capturedEntryId,
        Instant releasedAt

) implements Serializable {

}
