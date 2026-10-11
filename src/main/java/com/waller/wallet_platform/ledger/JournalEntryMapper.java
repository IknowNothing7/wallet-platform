package com.waller.wallet_platform.ledger;

import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface JournalEntryMapper {

    // Postings are loaded separately: JournalEntry has no association to them
    @Mapping(target = "postings", source = "postings")
    JournalEntryDto toDto(JournalEntry entry, List<PostingDto> postings);

    // For lists, where postings are left out to keep pages small
    @Mapping(target = "postings", ignore = true)
    JournalEntryDto toDto(JournalEntry entry);

}
