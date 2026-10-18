package com.waller.wallet_platform.ledger.service;

import com.waller.wallet_platform.account.entity.Account;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.ledger.dto.PostingDto;
import com.waller.wallet_platform.ledger.dto.PostingRequest;
import com.waller.wallet_platform.ledger.entity.Posting;
import com.waller.wallet_platform.ledger.entity.PostingDirection;
import com.waller.wallet_platform.ledger.mapper.PostingMapper;
import com.waller.wallet_platform.ledger.repository.JournalEntryRepository;
import com.waller.wallet_platform.ledger.repository.PostingRepository;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.security.AccountAccessChecker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.waller.wallet_platform.support.TestData.account;

import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
public class PostingServiceTest {

        private static final Long JOURNAL_ENTRY_ID = 7L;

        @Mock
        private PostingRepository postingRepository;

        @Mock
        private JournalEntryRepository journalEntryRepository;

        @Mock
        private AccountRepository accountRepository;

        @Mock
        private PostingMapper postingMapper;

        @Mock
        private AccountAccessChecker accountAccessChecker;

        @Mock
        private OutboxEventService outboxEventService;

        @InjectMocks
        private PostingServiceImpl postingService;

        @Test
        void createPostings_rejectsUnbalancedPostings() {
                when(journalEntryRepository.existsById(JOURNAL_ENTRY_ID)).thenReturn(true);
                List<PostingRequest> requests = List.of(
                                new PostingRequest(1L, PostingDirection.DEBIT, 100L, "USD"),
                                new PostingRequest(2L, PostingDirection.CREDIT, 90L, "USD"));

                assertThatThrownBy(() -> postingService.createPostings(JOURNAL_ENTRY_ID, requests))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                                                .isEqualTo(HttpStatus.BAD_REQUEST))
                                .hasMessageContaining("Postings are not balanced");

                verify(postingRepository, never()).saveAll(anyList());
                verify(outboxEventService, never()).record(anyString(), anyLong(), anyString(), any());
        }

        @Test
        void createPostings_savesBalancedPostingsAndRecordsEvent() {
                when(journalEntryRepository.existsById(JOURNAL_ENTRY_ID)).thenReturn(true);
                Account debited = account(1L, "USD");
                debited.setBalance(500L);
                Account credited = account(2L, "USD");
                when(accountRepository.findById(1L)).thenReturn(Optional.of(debited));
                when(accountRepository.findById(2L)).thenReturn(Optional.of(credited));
                when(postingRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
                List<PostingDto> dtos = List.of(PostingDto.builder().id(1L).build(),
                                PostingDto.builder().id(2L).build());
                when(postingMapper.toDtos(anyList())).thenReturn(dtos);

                List<PostingDto> result = postingService.createPostings(JOURNAL_ENTRY_ID, List.of(
                                new PostingRequest(1L, PostingDirection.DEBIT, 100L, "USD"),
                                new PostingRequest(2L, PostingDirection.CREDIT, 100L, "USD")));

                assertThat(result).isEqualTo(dtos);

                @SuppressWarnings("unchecked")
                ArgumentCaptor<List<Posting>> captor = ArgumentCaptor.forClass(List.class);
                verify(postingRepository).saveAll(captor.capture());
                assertThat(captor.getValue())
                                .extracting(Posting::getJournalEntryId, Posting::getAccountId, Posting::getDirection,
                                                Posting::getAmount)
                                .containsExactly(
                                                tuple(JOURNAL_ENTRY_ID, 1L, PostingDirection.DEBIT, 100L),
                                                tuple(JOURNAL_ENTRY_ID, 2L, PostingDirection.CREDIT, 100L));

                verify(outboxEventService).record(eq("journal_entry"), eq(JOURNAL_ENTRY_ID), eq("PostingsCreated"),
                                eq(dtos));

                assertThat(debited.getBalance()).isEqualTo(400L);
                assertThat(credited.getBalance()).isEqualTo(100L);
        }

        @Test
        void createPostingsWithoutJournalEntry() {
                when(journalEntryRepository.existsById(JOURNAL_ENTRY_ID)).thenReturn(false);

                List<PostingRequest> requests = List.of(
                        new PostingRequest(1L, PostingDirection.DEBIT, 100L, "USD"),
                        new PostingRequest(2L, PostingDirection.CREDIT, 100L, "USD"));

                assertThatThrownBy(() -> postingService.createPostings(JOURNAL_ENTRY_ID, requests))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                                                .isEqualTo(HttpStatus.NOT_FOUND))
                                .hasMessageContaining("Journal entry not found");

                verify(postingRepository, never()).saveAll(anyList());
                verify(outboxEventService, never()).record(anyString(), anyLong(), anyString(), any());
        }

        @Test
        void createPostingsWithUnknownAccountId() {
                when(journalEntryRepository.existsById(JOURNAL_ENTRY_ID)).thenReturn(true);
                when(accountRepository.findById(1L)).thenReturn(Optional.empty());
                List<PostingRequest> requests = List.of(
                        new PostingRequest(1L, PostingDirection.DEBIT, 100L, "USD"),
                        new PostingRequest(2L, PostingDirection.CREDIT, 100L, "USD"));


                assertThatThrownBy(() -> postingService.createPostings(JOURNAL_ENTRY_ID, requests))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                                                .isEqualTo(HttpStatus.NOT_FOUND))
                                .hasMessageContaining("Account 1 not found");

                verify(postingRepository, never()).saveAll(anyList());
                verify(outboxEventService, never()).record(anyString(), anyLong(), anyString(), any());
        }


    @Test
    void createPostingsWithDifferentCurrency() {
            Account account = account(1L,"USD");
        when(journalEntryRepository.existsById(JOURNAL_ENTRY_ID)).thenReturn(true);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        List<PostingRequest> requests = List.of(
                new PostingRequest(1L, PostingDirection.DEBIT, 100L, "ES"),
                new PostingRequest(2L, PostingDirection.CREDIT, 100L, "ES"));

        assertThatThrownBy(() -> postingService.createPostings(JOURNAL_ENTRY_ID, requests))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Posting currency does not match account " + account.getId() + " currency");

        verify(postingRepository, never()).saveAll(anyList());
        verify(outboxEventService, never()).record(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void createOnlyOnePosting() {
        when(journalEntryRepository.existsById(JOURNAL_ENTRY_ID)).thenReturn(true);

        List<PostingRequest> requests = List.of(
                new PostingRequest(1L, PostingDirection.DEBIT, 100L, "USD"));

        assertThatThrownBy(() -> postingService.createPostings(JOURNAL_ENTRY_ID, requests))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("At least two postings are required");

        verify(postingRepository, never()).saveAll(anyList());
        verify(outboxEventService, never()).record(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void createPostingsWithDifferentCurrencyBalancing() {
        when(journalEntryRepository.existsById(JOURNAL_ENTRY_ID)).thenReturn(true);
        List<PostingRequest> requests = List.of(
                new PostingRequest(1L, PostingDirection.DEBIT, 100L, "USD"),
                new PostingRequest(2L, PostingDirection.CREDIT, 100L, "EUR"));

        assertThatThrownBy(() -> postingService.createPostings(JOURNAL_ENTRY_ID, requests))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Postings are not balanced");

        verify(postingRepository, never()).saveAll(anyList());
        verify(outboxEventService, never()).record(anyString(), anyLong(), anyString(), any());
    }

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
}
