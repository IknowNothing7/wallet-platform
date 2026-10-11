package com.waller.wallet_platform.ledger.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import com.waller.wallet_platform.ledger.entity.JournalEntryType;

@Builder
public record JournalEntryDto(
        Long id,
        Instant createdAt,
        JournalEntryType entryType,
        String description,
        String referenceType,
        Long referenceId,
        List<PostingDto> postings

) implements Serializable {

}
