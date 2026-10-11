package com.waller.wallet_platform.outbox.dto;

import java.io.Serializable;
import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.Builder;
import com.waller.wallet_platform.outbox.entity.OutboxStatus;

@Builder
public record OutboxEventDto(
        Long id,
        Instant createdAt,
        Instant publishedAt,
        String aggregateType,
        Long aggregateId,
        String eventType,
        // Stored as JSON; written into the response as-is rather than as an escaped string
        @JsonRawValue String payload,
        OutboxStatus status,
        int attempts,
        String lastError

) implements Serializable {

}
