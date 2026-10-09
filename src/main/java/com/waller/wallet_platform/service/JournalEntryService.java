package com.waller.wallet_platform.service;

import java.util.List;

import com.waller.wallet_platform.model.dto.JournalEntryDto;
import com.waller.wallet_platform.model.response.PageResponse;

// Read-only: journal entries are written by the transfer/deposit/hold flows together with their postings
public interface JournalEntryService {

    /** The entry with all of its postings. */
    JournalEntryDto getJournalEntry(Long id);

    /** Newest first, without postings. */
    PageResponse<JournalEntryDto> getJournalEntries(int page, int size);

    /** Entries recorded for one business object, e.g. ("transfer", 42), each with its postings. */
    List<JournalEntryDto> getJournalEntriesByReference(String referenceType, Long referenceId);

}
