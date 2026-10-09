package com.waller.wallet_platform.model.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

import com.waller.wallet_platform.model.enums.JournalEntryType;

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
