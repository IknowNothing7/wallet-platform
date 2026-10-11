package com.waller.wallet_platform.ledger;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

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
