package com.waller.wallet_platform.ledger.service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.AccountType;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.ledger.dto.JournalEntryDto;
import com.waller.wallet_platform.ledger.dto.JournalEntryRequest;
import com.waller.wallet_platform.ledger.dto.PostingDto;
import com.waller.wallet_platform.ledger.dto.PostingRequest;
import com.waller.wallet_platform.ledger.entity.JournalEntry;
import com.waller.wallet_platform.ledger.entity.Posting;
import com.waller.wallet_platform.ledger.entity.PostingDirection;
import com.waller.wallet_platform.ledger.mapper.JournalEntryMapper;
import com.waller.wallet_platform.ledger.mapper.PostingMapper;
import com.waller.wallet_platform.ledger.repository.JournalEntryRepository;
import com.waller.wallet_platform.ledger.repository.PostingRepository;
import lombok.RequiredArgsConstructor;

/**
 * Package-private: the rest of the application sees only LedgerService.
 *
 * <p>Locking: every account of the entry is row-locked here (SELECT ... FOR UPDATE), user accounts first, then system
 * accounts, each by id. That is the one locking strategy for money: every caller inherits it, and a caller that
 * needs to check something first (a transfer's available balance, a capture's destination) locks its user accounts
 * the same way beforehand, so re-locking them here is a no-op and the order still holds. @Version on Account only
 * guards against a write that bypasses this path.
 *
 * <p>No events: the ledger is an internal record. Each business operation publishes its own event (TransferCompleted,
 * DepositSucceeded, HoldCaptured) in the same transaction.
 */
@Service
@RequiredArgsConstructor
class LedgerServiceImpl implements LedgerService {

    private static final String INSUFFICIENT_FUNDS = "Insufficient funds";

    private final JournalEntryRepository journalEntryRepository;
    private final PostingRepository postingRepository;
    private final AccountRepository accountRepository;
    private final JournalEntryMapper journalEntryMapper;
    private final PostingMapper postingMapper;

    // MANDATORY: the entry must commit or roll back together with the domain change that caused it
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public JournalEntryDto post(JournalEntryRequest request) {
        // Callers build these in code, so a bad one is a bug, not a client error
        if (request.getReferenceType() == null || request.getReferenceId() == null) {
            throw new IllegalArgumentException("A journal entry needs a reference");
        }
        List<PostingRequest> lines = request.getPostings();
        requireBalanced(lines);

        Map<Long, Long> deltas = new HashMap<>();
        for (PostingRequest line : lines) {
            long signed = line.getDirection() == PostingDirection.CREDIT ? line.getAmount() : -line.getAmount();
            deltas.merge(line.getAccountId(), signed, Math::addExact);
        }
        // In lock order; nothing below reads a balance before its row is locked
        Map<Long, Account> accounts = new LinkedHashMap<>();
        accountRepository.findAllByIdForUpdateInLockOrder(deltas.keySet(), AccountType.SYSTEM)
                .forEach(account -> accounts.put(account.getId(), account));
        for (PostingRequest line : lines) {
            Account account = accounts.get(line.getAccountId());
            if (account == null) {
                throw new IllegalArgumentException("Account " + line.getAccountId() + " does not exist");
            }
            if (!account.getCurrency().equals(line.getCurrency())) {
                throw new IllegalArgumentException("Posting currency does not match account " + account.getId());
            }
        }
        // Only platform accounts may go negative (BANK_CLEARING is, by design). Checked for all accounts before any
        // balance changes; chk_account_balance_non_negative backs this up in the schema.
        accounts.values().forEach(account -> {
            if (account.getType() != AccountType.SYSTEM && account.getBalance() + deltas.get(account.getId()) < 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, INSUFFICIENT_FUNDS);
            }
        });

        JournalEntry entry = new JournalEntry();
        entry.setEntryType(request.getEntryType());
        entry.setDescription(request.getDescription());
        entry.setReferenceType(request.getReferenceType());
        entry.setReferenceId(request.getReferenceId());
        JournalEntry saved = journalEntryRepository.save(entry);

        // The locked, managed rows are changed directly, so the caller's copies stay current for the rest of the
        // transaction
        accounts.values().forEach(account -> account.setBalance(account.getBalance() + deltas.get(account.getId())));

        List<Posting> postings = lines.stream().map(line -> {
            Posting posting = new Posting();
            posting.setJournalEntryId(saved.getId());
            posting.setAccountId(line.getAccountId());
            posting.setDirection(line.getDirection());
            posting.setAmount(line.getAmount());
            posting.setCurrency(line.getCurrency());
            return posting;
        }).toList();
        List<PostingDto> postingDtos = postingMapper.toDtos(postingRepository.saveAll(postings));
        return journalEntryMapper.toDto(saved, postingDtos);
    }

    private static void requireBalanced(List<PostingRequest> lines) {
        if (lines == null || lines.size() < 2) {
            throw new IllegalArgumentException("At least two postings are required");
        }
        Map<String, Long> net = new HashMap<>();
        for (PostingRequest line : lines) {
            if (line.getAmount() == null || line.getAmount() <= 0) {
                throw new IllegalArgumentException("Posting amounts must be positive");
            }
            long signed = line.getDirection() == PostingDirection.DEBIT ? line.getAmount() : -line.getAmount();
            net.merge(line.getCurrency(), signed, Math::addExact);
        }
        if (net.values().stream().anyMatch(v -> v != 0)) {
            throw new IllegalArgumentException("Postings are not balanced");
        }
    }

}
