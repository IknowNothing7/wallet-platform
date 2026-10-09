package com.waller.wallet_platform.model.dto;

import java.io.Serializable;
import java.time.Instant;

import com.waller.wallet_platform.model.enums.PostingDirection;

import lombok.Builder;

@Builder
public record PostingDto(
        Long id,
        Long journalEntryId,
        Long accountId,
        PostingDirection direction,
        Long amount,
        String currency,
        Instant createdAt

) implements Serializable {

}
