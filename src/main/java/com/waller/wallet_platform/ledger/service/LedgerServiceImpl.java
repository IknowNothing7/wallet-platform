package com.waller.wallet_platform.ledger.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import lombok.RequiredArgsConstructor;

// Package-private: the rest of the application sees only LedgerService
@Service
@RequiredArgsConstructor
class LedgerServiceImpl implements LedgerService {

    private static final String AGGREGATE_TYPE = "journal_entry";
    private static final String JOURNAL_ENTRY_CREATED = "JournalEntryCreated";
    private static final String POSTINGS_CREATED = "PostingsCreated";
    private static final String INSUFFICIENT_FUNDS = "Insufficient funds";

    private final JournalEntryRepository journalEntryRepository;
    private final PostingRepository postingRepository;
    private final AccountRepository accountRepository;
    private final JournalEntryMapper journalEntryMapper;
    private final PostingMapper postingMapper;
    private final OutboxEventService outboxEventService;

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
        Map<Long, Account> accounts = accountRepository.findAllById(
                        lines.stream().map(PostingRequest::getAccountId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        for (PostingRequest line : lines) {
            Account account = accounts.get(line.getAccountId());
            if (account == null) {
                throw new IllegalArgumentException("Account " + line.getAccountId() + " does not exist");
            }
            if (!account.getCurrency().equals(line.getCurrency())) {
                throw new IllegalArgumentException("Posting currency does not match account " + account.getId());
            }
        }

        JournalEntry entry = new JournalEntry();
        entry.setEntryType(request.getEntryType());
        entry.setDescription(request.getDescription());
        entry.setReferenceType(request.getReferenceType());
        entry.setReferenceId(request.getReferenceId());
        JournalEntry saved = journalEntryRepository.save(entry);
        // Header only: the postings follow as their own PostingsCreated event on the same aggregate
        outboxEventService.record(AGGREGATE_TYPE, saved.getId(), JOURNAL_ENTRY_CREATED, journalEntryMapper.toDto(saved));

        applyToBalances(lines, accounts);

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
        outboxEventService.record(AGGREGATE_TYPE, saved.getId(), POSTINGS_CREATED, postingDtos);
        return journalEntryMapper.toDto(saved, postingDtos);
    }

    // One atomic UPDATE per account, which takes the row lock itself, so a busy system account (FEES) makes concurrent
    // entries wait their turn instead of failing on @Version. A user account that would go negative updates no row:
    // the whole entry rolls back. chk_account_balance_non_negative backs this up in the schema.
    //
    // Lock order: user accounts first, then system accounts, each by id. Transfers and captures have already locked
    // their user accounts (SELECT ... FOR UPDATE) when they get here, so every writer takes user rows before system
    // rows. Plain id order would let a deposit hold FEES while waiting for a user row that a transfer holds while
    // waiting for FEES: a deadlock.
    private void applyToBalances(List<PostingRequest> lines, Map<Long, Account> accounts) {
        Map<Long, Long> deltas = new HashMap<>();
        for (PostingRequest line : lines) {
            long signed = line.getDirection() == PostingDirection.CREDIT ? line.getAmount() : -line.getAmount();
            deltas.merge(line.getAccountId(), signed, Math::addExact);
        }
        deltas.keySet().stream()
                .map(accounts::get)
                .sorted(Comparator.comparing((Account a) -> a.getType() == AccountType.SYSTEM).thenComparing(Account::getId))
                .forEach(account -> {
                    long delta = deltas.get(account.getId());
                    if (delta != 0 && accountRepository.applyBalanceDelta(account.getId(), delta) == 0) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, INSUFFICIENT_FUNDS);
                    }
                });
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
