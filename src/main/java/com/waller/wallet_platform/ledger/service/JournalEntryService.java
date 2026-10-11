package com.waller.wallet_platform.ledger.service;

import java.util.List;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.ledger.dto.JournalEntryDto;
import com.waller.wallet_platform.ledger.dto.JournalEntryRequest;

// Journal entries are always written together with their balanced postings
public interface JournalEntryService {

    /** Creates the entry and its postings in one transaction. */
    JournalEntryDto createJournalEntry(JournalEntryRequest request);

    /** The entry with all of its postings. */
    JournalEntryDto getJournalEntry(Long id);

    /** Newest first, without postings. */
    PageResponse<JournalEntryDto> getJournalEntries(int page, int size);

    /** Entries recorded for one business object, e.g. ("transfer", 42), each with its postings. */
    List<JournalEntryDto> getJournalEntriesByReference(String referenceType, Long referenceId);

}
