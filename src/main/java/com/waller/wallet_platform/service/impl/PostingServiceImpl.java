package com.waller.wallet_platform.service.impl;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.mapper.PostingMapper;
import com.waller.wallet_platform.model.dto.PostingDto;
import com.waller.wallet_platform.model.entites.Posting;
import com.waller.wallet_platform.model.response.PageResponse;
import com.waller.wallet_platform.repositories.PostingRepository;
import com.waller.wallet_platform.security.AccountAccessChecker;
import com.waller.wallet_platform.service.PostingService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PostingServiceImpl implements PostingService {

    private static final String POSTING_NOT_FOUND = "Posting not found";
    private static final String ACCOUNT_NOT_FOUND = "Account not found";

    private final PostingRepository postingRepository;
    private final PostingMapper postingMapper;
    private final AccountAccessChecker accountAccessChecker;

    @Override
    @Transactional(readOnly = true)
    public PostingDto getPosting(Long id) {
        Posting posting = postingRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, POSTING_NOT_FOUND));
        // Someone else's posting looks the same as a missing one, so ids can't be probed
        if (!accountAccessChecker.canAccess(posting.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, POSTING_NOT_FOUND);
        }
        return postingMapper.toDto(posting);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PostingDto> getAccountPostings(Long accountId, int page, int size) {
        if (!accountAccessChecker.canAccess(accountId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ACCOUNT_NOT_FOUND);
        }
        return PageResponse.of(
                postingRepository.findByAccountIdOrderByCreatedAtDescIdDesc(accountId, PageRequest.of(page, size)),
                postingMapper::toDto);
    }

    // Admin-only (enforced in the controller): an entry spans several accounts, often of different users
    @Override
    @Transactional(readOnly = true)
    public List<PostingDto> getJournalEntryPostings(Long journalEntryId) {
        return postingMapper.toDtos(postingRepository.findByJournalEntryIdOrderByIdAsc(journalEntryId));
    }

}
