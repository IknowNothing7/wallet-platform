package com.waller.wallet_platform.ledger.service;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import lombok.RequiredArgsConstructor;
import com.waller.wallet_platform.ledger.dto.JournalEntryDto;
import com.waller.wallet_platform.ledger.dto.JournalEntryRequest;
import com.waller.wallet_platform.ledger.dto.PostingDto;
import com.waller.wallet_platform.ledger.entity.JournalEntry;
import com.waller.wallet_platform.ledger.mapper.JournalEntryMapper;
import com.waller.wallet_platform.ledger.mapper.PostingMapper;
import com.waller.wallet_platform.ledger.repository.JournalEntryRepository;
import com.waller.wallet_platform.ledger.repository.PostingRepository;

// Admin-only (enforced in the controller): an entry spans several accounts, often of different users
@Service
@RequiredArgsConstructor
public class JournalEntryServiceImpl implements JournalEntryService {

    private static final String JOURNAL_ENTRY_NOT_FOUND = "Journal entry not found";
    private static final String AGGREGATE_TYPE = "journal_entry";
    private static final String JOURNAL_ENTRY_CREATED = "JournalEntryCreated";

    private final JournalEntryRepository journalEntryRepository;
    private final PostingRepository postingRepository;
    private final JournalEntryMapper journalEntryMapper;
    private final PostingMapper postingMapper;
    private final PostingService postingService;
    private final OutboxEventService outboxEventService;

    @Override
    @Transactional
    public JournalEntryDto createJournalEntry(JournalEntryRequest request) {
        JournalEntry entry = new JournalEntry();
        entry.setEntryType(request.getEntryType());
        entry.setDescription(request.getDescription());
        entry.setReferenceType(request.getReferenceType());
        entry.setReferenceId(request.getReferenceId());
        JournalEntry saved = journalEntryRepository.save(entry);

        // Header only: the postings follow as their own PostingsCreated event on the same aggregate
        outboxEventService.record(AGGREGATE_TYPE, saved.getId(), JOURNAL_ENTRY_CREATED, journalEntryMapper.toDto(saved));

        // Throws on an unbalanced set, rolling back the entry with it
        List<PostingDto> postings = postingService.createPostings(saved.getId(), request.getPostings());
        return journalEntryMapper.toDto(saved, postings);
    }

    @Override
    @Transactional(readOnly = true)
    public JournalEntryDto getJournalEntry(Long id) {
        JournalEntry entry = journalEntryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, JOURNAL_ENTRY_NOT_FOUND));
        return withPostings(entry);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<JournalEntryDto> getJournalEntries(int page, int size) {
        return PageResponse.of(
                journalEntryRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(page, size)),
                journalEntryMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JournalEntryDto> getJournalEntriesByReference(String referenceType, Long referenceId) {
        return journalEntryRepository.findByReferenceTypeAndReferenceIdOrderByIdAsc(referenceType, referenceId)
                .stream()
                .map(this::withPostings)
                .toList();
    }

    private JournalEntryDto withPostings(JournalEntry entry) {
        return journalEntryMapper.toDto(entry,
                postingMapper.toDtos(postingRepository.findByJournalEntryIdOrderByIdAsc(entry.getId())));
    }

}
