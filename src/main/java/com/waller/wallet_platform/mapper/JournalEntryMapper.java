package com.waller.wallet_platform.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.waller.wallet_platform.model.dto.JournalEntryDto;
import com.waller.wallet_platform.model.dto.PostingDto;
import com.waller.wallet_platform.model.entites.JournalEntry;

@Mapper(componentModel = "spring")
public interface JournalEntryMapper {

    // Postings are loaded separately: JournalEntry has no association to them
    @Mapping(target = "postings", source = "postings")
    JournalEntryDto toDto(JournalEntry entry, List<PostingDto> postings);

    // For lists, where postings are left out to keep pages small
    @Mapping(target = "postings", ignore = true)
    JournalEntryDto toDto(JournalEntry entry);

}
