package com.waller.wallet_platform.ledger.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.security.AccountAccessChecker;
import lombok.RequiredArgsConstructor;
import com.waller.wallet_platform.ledger.mapper.PostingMapper;
import com.waller.wallet_platform.ledger.repository.JournalEntryRepository;
import com.waller.wallet_platform.ledger.repository.PostingRepository;
import com.waller.wallet_platform.ledger.dto.PostingDto;
import com.waller.wallet_platform.ledger.dto.PostingRequest;
import com.waller.wallet_platform.ledger.entity.Posting;
import com.waller.wallet_platform.ledger.entity.PostingDirection;

@Service
@RequiredArgsConstructor
public class PostingServiceImpl implements PostingService {

    private static final String POSTING_NOT_FOUND = "Posting not found";
    private static final String ACCOUNT_NOT_FOUND = "Account not found";
    private static final String JOURNAL_ENTRY_NOT_FOUND = "Journal entry not found";

    private final PostingRepository postingRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AccountRepository accountRepository;
    private final PostingMapper postingMapper;
    private final AccountAccessChecker accountAccessChecker;

    // Admin-only (enforced in the controller); joins the caller's transaction when called from JournalEntryService
    @Override
    @Transactional
    public List<PostingDto> createPostings(Long journalEntryId, List<PostingRequest> requests) {
        if (!journalEntryRepository.existsById(journalEntryId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, JOURNAL_ENTRY_NOT_FOUND);
        }
        requireBalanced(requests);

        List<Posting> postings = requests.stream().map(r -> {
            Account account = accountRepository.findById(r.getAccountId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Account " + r.getAccountId() + " not found"));
            if (!account.getCurrency().equals(r.getCurrency())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Posting currency does not match account " + account.getId() + " currency");
            }
            Posting posting = new Posting();
            posting.setJournalEntryId(journalEntryId);
            posting.setAccountId(account.getId());
            posting.setDirection(r.getDirection());
            posting.setAmount(r.getAmount());
            posting.setCurrency(r.getCurrency());
            return posting;
        }).toList();
        // TODO: apply postings to account balances

        return postingMapper.toDtos(postingRepository.saveAll(postings));
    }

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

    // Debits must equal credits within every currency, or the ledger drifts
    private void requireBalanced(List<PostingRequest> requests) {
        if (requests == null || requests.size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least two postings are required");
        }
        Map<String, Long> net = new HashMap<>();
        for (PostingRequest r : requests) {
            long signed = r.getDirection() == PostingDirection.DEBIT ? r.getAmount() : -r.getAmount();
            net.merge(r.getCurrency(), signed, Math::addExact);
        }
        if (net.values().stream().anyMatch(v -> v != 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Postings are not balanced");
        }
    }

}
