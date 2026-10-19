package com.waller.wallet_platform.ledger.service;

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
import com.waller.wallet_platform.ledger.dto.PostingDto;
import com.waller.wallet_platform.ledger.entity.Posting;
import com.waller.wallet_platform.ledger.mapper.PostingMapper;
import com.waller.wallet_platform.ledger.repository.PostingRepository;
import com.waller.wallet_platform.security.AccountAccessChecker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

// Read side only; writes are LedgerServiceImplTest's
@ExtendWith(MockitoExtension.class)
public class PostingServiceTest {

    @Mock
    private PostingRepository postingRepository;

    @Mock
    private PostingMapper postingMapper;

    @Mock
    private AccountAccessChecker accountAccessChecker;

    @InjectMocks
    private PostingServiceImpl postingService;

    @Test
    void tryToGetPostingsFromDifferentAcc() {
        Posting posting = new Posting();
        posting.setAccountId(1L);
        when(postingRepository.findById(1L)).thenReturn(Optional.of(posting));
        when(accountAccessChecker.canAccess(1L)).thenReturn(false);

        assertThatThrownBy(() -> postingService.getPosting(1L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Posting not found");
    }

    @Test
    void getPosting_ofOwnAccount_returnsIt() {
        Posting posting = new Posting();
        posting.setAccountId(1L);
        PostingDto dto = PostingDto.builder().id(5L).build();
        when(postingRepository.findById(5L)).thenReturn(Optional.of(posting));
        when(accountAccessChecker.canAccess(1L)).thenReturn(true);
        when(postingMapper.toDto(posting)).thenReturn(dto);

        assertThat(postingService.getPosting(5L)).isSameAs(dto);
    }

    @Test
    void getPosting_unknown_isNotFound() {
        when(postingRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postingService.getPosting(5L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // Someone else's account looks the same as a missing one, and its postings are never read
    @Test
    void getAccountPostings_ofAnotherUsersAccount_isNotFound() {
        when(accountAccessChecker.canAccess(1L)).thenReturn(false);

        assertThatThrownBy(() -> postingService.getAccountPostings(1L, 0, 20))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Account not found");
        verifyNoInteractions(postingRepository);
    }

    @Test
    void getAccountPostings_ofOwnAccount_returnsTheRequestedPage() {
        Posting posting = new Posting();
        PostingDto dto = PostingDto.builder().id(5L).build();
        when(accountAccessChecker.canAccess(1L)).thenReturn(true);
        when(postingRepository.findByAccountIdOrderByCreatedAtDescIdDesc(1L, PageRequest.of(2, 20)))
                .thenReturn(new PageImpl<>(List.of(posting), PageRequest.of(2, 20), 41));
        when(postingMapper.toDto(posting)).thenReturn(dto);

        PageResponse<PostingDto> page = postingService.getAccountPostings(1L, 2, 20);

        assertThat(page.content()).containsExactly(dto);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(41);
    }

    @Test
    void getJournalEntryPostings_returnsThemInOrder() {
        List<Posting> postings = List.of(new Posting(), new Posting());
        List<PostingDto> dtos = List.of(PostingDto.builder().id(1L).build(), PostingDto.builder().id(2L).build());
        when(postingRepository.findByJournalEntryIdOrderByIdAsc(50L)).thenReturn(postings);
        when(postingMapper.toDtos(postings)).thenReturn(dtos);

        assertThat(postingService.getJournalEntryPostings(50L)).isEqualTo(dtos);
    }
}
