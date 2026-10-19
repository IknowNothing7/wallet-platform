package com.waller.wallet_platform.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.ledger.dto.JournalEntryDto;
import com.waller.wallet_platform.ledger.dto.PostingDto;
import com.waller.wallet_platform.ledger.entity.JournalEntry;
import com.waller.wallet_platform.ledger.entity.Posting;
import com.waller.wallet_platform.ledger.mapper.JournalEntryMapper;
import com.waller.wallet_platform.ledger.mapper.PostingMapper;
import com.waller.wallet_platform.ledger.repository.JournalEntryRepository;
import com.waller.wallet_platform.ledger.repository.PostingRepository;

// Read side only; writes are LedgerServiceImplTest's. Admin access is the controller's (JournalEntryController).
@ExtendWith(MockitoExtension.class)
class JournalEntryServiceTest {

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private PostingRepository postingRepository;

    @Mock
    private JournalEntryMapper journalEntryMapper;

    @Mock
    private PostingMapper postingMapper;

    @InjectMocks
    private JournalEntryServiceImpl journalEntryService;

    @Test
    void getJournalEntry_returnsItWithItsPostings() {
        JournalEntry entry = entry(50L);
        JournalEntryDto dto = stubWithPostings(entry);
        when(journalEntryRepository.findById(50L)).thenReturn(Optional.of(entry));

        assertThat(journalEntryService.getJournalEntry(50L)).isSameAs(dto);
    }

    @Test
    void getJournalEntry_unknown_isNotFound() {
        when(journalEntryRepository.findById(50L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> journalEntryService.getJournalEntry(50L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Journal entry not found");
    }

    // Pages leave the postings out (JournalEntryMapper#toDto(JournalEntry))
    @Test
    void getJournalEntries_returnsTheRequestedPageWithoutPostings() {
        JournalEntry entry = entry(50L);
        JournalEntryDto dto = JournalEntryDto.builder().id(50L).build();
        when(journalEntryRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(1, 10)))
                .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(1, 10), 11));
        when(journalEntryMapper.toDto(entry)).thenReturn(dto);

        PageResponse<JournalEntryDto> page = journalEntryService.getJournalEntries(1, 10);

        assertThat(page.content()).containsExactly(dto);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.totalElements()).isEqualTo(11);
    }

    @Test
    void getJournalEntriesByReference_returnsEachWithItsPostings() {
        JournalEntry booking = entry(50L);
        JournalEntry reversal = entry(51L);
        JournalEntryDto bookingDto = stubWithPostings(booking);
        JournalEntryDto reversalDto = stubWithPostings(reversal);
        when(journalEntryRepository.findByReferenceTypeAndReferenceIdOrderByIdAsc("transfer", 7L))
                .thenReturn(List.of(booking, reversal));

        assertThat(journalEntryService.getJournalEntriesByReference("transfer", 7L))
                .containsExactly(bookingDto, reversalDto);
    }

    private JournalEntryDto stubWithPostings(JournalEntry entry) {
        List<Posting> postings = List.of(new Posting());
        List<PostingDto> postingDtos = List.of(PostingDto.builder().journalEntryId(entry.getId()).build());
        JournalEntryDto dto = JournalEntryDto.builder().id(entry.getId()).postings(postingDtos).build();
        when(postingRepository.findByJournalEntryIdOrderByIdAsc(entry.getId())).thenReturn(postings);
        when(postingMapper.toDtos(postings)).thenReturn(postingDtos);
        when(journalEntryMapper.toDto(entry, postingDtos)).thenReturn(dto);
        return dto;
    }

    private static JournalEntry entry(Long id) {
        JournalEntry entry = new JournalEntry();
        entry.setId(id);
        return entry;
    }
}
