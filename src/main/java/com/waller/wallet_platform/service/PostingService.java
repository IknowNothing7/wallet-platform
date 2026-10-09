package com.waller.wallet_platform.service;

import java.util.List;

import com.waller.wallet_platform.model.dto.PostingDto;
import com.waller.wallet_platform.model.response.PageResponse;

// Read-only: postings are written in balanced pairs by the transfer/deposit/hold flows, never on their own
public interface PostingService {

    PostingDto getPosting(Long id);

    PageResponse<PostingDto> getAccountPostings(Long accountId, int page, int size);

    List<PostingDto> getJournalEntryPostings(Long journalEntryId);

}
