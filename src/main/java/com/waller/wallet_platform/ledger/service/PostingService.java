package com.waller.wallet_platform.ledger.service;

import java.util.List;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.ledger.dto.PostingDto;
import com.waller.wallet_platform.ledger.dto.PostingRequest;

// Postings are only ever written as a balanced set (debits == credits per currency)
public interface PostingService {

    /** Adds a balanced set of postings to an existing journal entry. */
    List<PostingDto> createPostings(Long journalEntryId, List<PostingRequest> requests);

    PostingDto getPosting(Long id);

    PageResponse<PostingDto> getAccountPostings(Long accountId, int page, int size);

    List<PostingDto> getJournalEntryPostings(Long journalEntryId);

}
