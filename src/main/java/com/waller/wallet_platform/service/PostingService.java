package com.waller.wallet_platform.service;

import java.util.List;

import com.waller.wallet_platform.model.dto.PostingDto;
import com.waller.wallet_platform.model.request.PostingRequest;
import com.waller.wallet_platform.model.response.PageResponse;

// Postings are only ever written as a balanced set (debits == credits per currency)
public interface PostingService {

    /** Adds a balanced set of postings to an existing journal entry. */
    List<PostingDto> createPostings(Long journalEntryId, List<PostingRequest> requests);

    PostingDto getPosting(Long id);

    PageResponse<PostingDto> getAccountPostings(Long accountId, int page, int size);

    List<PostingDto> getJournalEntryPostings(Long journalEntryId);

}
