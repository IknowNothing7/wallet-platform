package com.waller.wallet_platform.service.impl;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.mapper.JournalEntryMapper;
import com.waller.wallet_platform.mapper.PostingMapper;
import com.waller.wallet_platform.model.dto.JournalEntryDto;
import com.waller.wallet_platform.model.entites.JournalEntry;
import com.waller.wallet_platform.model.response.PageResponse;
import com.waller.wallet_platform.repositories.JournalEntryRepository;
import com.waller.wallet_platform.repositories.PostingRepository;
import com.waller.wallet_platform.service.JournalEntryService;

import lombok.RequiredArgsConstructor;

// Admin-only (enforced in the controller): an entry spans several accounts, often of different users
@Service
@RequiredArgsConstructor
public class JournalEntryServiceImpl implements JournalEntryService {

    private static final String JOURNAL_ENTRY_NOT_FOUND = "Journal entry not found";

    private final JournalEntryRepository journalEntryRepository;
    private final PostingRepository postingRepository;
    private final JournalEntryMapper journalEntryMapper;
    private final PostingMapper postingMapper;

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
