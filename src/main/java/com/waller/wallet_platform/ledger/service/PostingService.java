package com.waller.wallet_platform.ledger.service;

import java.util.List;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.ledger.dto.PostingDto;

// Read side of the ledger; postings are only ever written through LedgerService, together with their entry
public interface PostingService {

    PostingDto getPosting(Long id);

    PageResponse<PostingDto> getAccountPostings(Long accountId, int page, int size);

    List<PostingDto> getJournalEntryPostings(Long journalEntryId);

}
