package com.waller.wallet_platform.ledger.service;

import static com.waller.wallet_platform.support.TestData.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.AccountType;
import com.waller.wallet_platform.account.entity.SystemAccountKind;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.ledger.dto.JournalEntryDto;
import com.waller.wallet_platform.ledger.dto.JournalEntryRequest;
import com.waller.wallet_platform.ledger.dto.PostingDto;
import com.waller.wallet_platform.ledger.dto.PostingRequest;
import com.waller.wallet_platform.ledger.entity.JournalEntry;
import com.waller.wallet_platform.ledger.entity.JournalEntryType;
import com.waller.wallet_platform.ledger.entity.Posting;
import com.waller.wallet_platform.ledger.entity.PostingDirection;
import com.waller.wallet_platform.ledger.mapper.JournalEntryMapper;
import com.waller.wallet_platform.ledger.mapper.PostingMapper;
import com.waller.wallet_platform.ledger.repository.JournalEntryRepository;
import com.waller.wallet_platform.ledger.repository.PostingRepository;

@ExtendWith(MockitoExtension.class)
class LedgerServiceImplTest {

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private PostingRepository postingRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private JournalEntryMapper journalEntryMapper;

    @Mock
    private PostingMapper postingMapper;

    @InjectMocks
    private LedgerServiceImpl ledgerService;

    @Captor
    private ArgumentCaptor<JournalEntry> savedEntry;

    @Captor
    private ArgumentCaptor<List<Posting>> savedPostings;

    private Account bankClearing;
    private Account fees;
    private Account user;

    @BeforeEach
    void setUp() {
        // System accounts get the low ids, as V3 creates them first
        bankClearing = systemAccount(1L, SystemAccountKind.BANK_CLEARING);
        fees = systemAccount(2L, SystemAccountKind.FEES);
        user = account(10L, "USD");
    }

    @Test
    void post_changesTheLockedBalancesAndWritesPostings() {
        bankClearing.setBalance(-500L);
        user.setBalance(500L);
        stubLocked(user, bankClearing);
        stubWrites();

        ledgerService.post(entry(
                line(1L, PostingDirection.DEBIT, 100),
                line(10L, PostingDirection.CREDIT, 100)));

        assertThat(bankClearing.getBalance()).isEqualTo(-600L);
        assertThat(user.getBalance()).isEqualTo(600L);
        verify(postingRepository).saveAll(anyList());
    }

    // What is written is the record of the money movement: an entry with the request's reference, one posting per line
    @Test
    void post_writesTheEntryAndOnePostingPerLineAndReturnsThem() {
        stubLocked(user, bankClearing);
        stubWrites();
        List<PostingDto> postingDtos = List.of(PostingDto.builder().id(1L).build());
        JournalEntryDto entryDto = JournalEntryDto.builder().id(50L).build();
        when(postingMapper.toDtos(anyList())).thenReturn(postingDtos);
        when(journalEntryMapper.toDto(any(JournalEntry.class), eq(postingDtos))).thenReturn(entryDto);

        JournalEntryDto result = ledgerService.post(new JournalEntryRequest(
                JournalEntryType.DEPOSIT, "deposit 7", "deposit", 7L,
                List.of(line(1L, PostingDirection.DEBIT, 100), line(10L, PostingDirection.CREDIT, 100))));

        assertThat(result).isSameAs(entryDto);
        verify(journalEntryRepository).save(savedEntry.capture());
        assertThat(savedEntry.getValue())
                .extracting(JournalEntry::getEntryType, JournalEntry::getDescription,
                        JournalEntry::getReferenceType, JournalEntry::getReferenceId)
                .containsExactly(JournalEntryType.DEPOSIT, "deposit 7", "deposit", 7L);
        verify(postingRepository).saveAll(savedPostings.capture());
        assertThat(savedPostings.getValue())
                .extracting(Posting::getJournalEntryId, Posting::getAccountId, Posting::getDirection,
                        Posting::getAmount, Posting::getCurrency)
                .containsExactly(
                        tuple(50L, 1L, PostingDirection.DEBIT, 100L, "USD"),
                        tuple(50L, 10L, PostingDirection.CREDIT, 100L, "USD"));
    }

    // Every account of the entry in one locking query, whose order (users, then system) the repository applies
    @Test
    void post_locksEveryAccountOfTheEntryInOneOrderedQuery() {
        stubLocked(user, bankClearing, fees);
        stubWrites();

        ledgerService.post(entry(
                line(1L, PostingDirection.DEBIT, 100),
                line(10L, PostingDirection.CREDIT, 98),
                line(2L, PostingDirection.CREDIT, 2)));

        verify(accountRepository).findAllByIdForUpdateInLockOrder(Set.of(1L, 2L, 10L), AccountType.SYSTEM);
        verifyNoMoreInteractions(accountRepository);
    }

    @Test
    void post_userAccountWouldGoNegative_throwsConflictAndChangesNothing() {
        Account other = account(11L, "USD");
        other.setBalance(0L);
        user.setBalance(99L);
        stubLocked(user, other);

        assertThatThrownBy(() -> ledgerService.post(entry(
                line(10L, PostingDirection.DEBIT, 100),
                line(11L, PostingDirection.CREDIT, 100))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("Insufficient funds");

        assertThat(user.getBalance()).isEqualTo(99L);
        assertThat(other.getBalance()).isZero();
        verifyNoInteractions(journalEntryRepository, postingRepository);
    }

    @Test
    void post_userAccountMaySpendItsWholeBalance() {
        Account other = account(11L, "USD");
        other.setBalance(0L);
        user.setBalance(100L);
        stubLocked(user, other);
        stubWrites();

        ledgerService.post(entry(
                line(10L, PostingDirection.DEBIT, 100),
                line(11L, PostingDirection.CREDIT, 100)));

        assertThat(user.getBalance()).isZero();
        assertThat(other.getBalance()).isEqualTo(100L);
    }

    @Test
    void post_systemAccountMayGoNegative() {
        bankClearing.setBalance(0L);
        user.setBalance(0L);
        stubLocked(user, bankClearing);
        stubWrites();

        ledgerService.post(entry(
                line(1L, PostingDirection.DEBIT, 100),
                line(10L, PostingDirection.CREDIT, 100)));

        assertThat(bankClearing.getBalance()).isEqualTo(-100L);
    }

    @Test
    void post_unbalanced_isRefusedBeforeAnythingIsWritten() {
        assertRefused("Postings are not balanced",
                line(1L, PostingDirection.DEBIT, 100),
                line(10L, PostingDirection.CREDIT, 90));
    }

    @Test
    void post_balancedOnlyAcrossCurrencies_isRefused() {
        assertRefused("Postings are not balanced",
                line(1L, PostingDirection.DEBIT, 100),
                new PostingRequest(10L, PostingDirection.CREDIT, 100L, "EUR"));
    }

    @Test
    void post_singlePosting_isRefused() {
        assertRefused("At least two postings are required", line(1L, PostingDirection.DEBIT, 100));
    }

    @Test
    void post_nonPositiveAmount_isRefused() {
        assertRefused("Posting amounts must be positive",
                line(1L, PostingDirection.DEBIT, 0),
                line(10L, PostingDirection.CREDIT, 0));
    }

    @Test
    void post_withoutReference_isRefused() {
        JournalEntryRequest request = new JournalEntryRequest(JournalEntryType.TRANSFER, "no reference", null, null,
                List.of(line(1L, PostingDirection.DEBIT, 100), line(10L, PostingDirection.CREDIT, 100)));

        assertThatThrownBy(() -> ledgerService.post(request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(journalEntryRepository, accountRepository, postingRepository);
    }

    @Test
    void post_unknownAccount_isRefusedBeforeAnythingIsWritten() {
        stubLocked(user);

        assertThatThrownBy(() -> ledgerService.post(entry(
                line(1L, PostingDirection.DEBIT, 100),
                line(10L, PostingDirection.CREDIT, 100))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account 1 does not exist");
        verifyNoInteractions(journalEntryRepository, postingRepository);
    }

    @Test
    void post_currencyOfAnotherAccount_isRefusedBeforeAnythingIsWritten() {
        Account eur = account(11L, "EUR");
        stubLocked(user, eur);

        assertThatThrownBy(() -> ledgerService.post(entry(
                line(10L, PostingDirection.DEBIT, 100),
                line(11L, PostingDirection.CREDIT, 100))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Posting currency does not match account 11");
        verifyNoInteractions(journalEntryRepository, postingRepository);
    }

    private void assertRefused(String message, PostingRequest... lines) {
        assertThatThrownBy(() -> ledgerService.post(entry(lines)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
        verifyNoInteractions(journalEntryRepository, accountRepository, postingRepository);
    }

    private void stubLocked(Account... accounts) {
        when(accountRepository.findAllByIdForUpdateInLockOrder(anyCollection(), eq(AccountType.SYSTEM)))
                .thenReturn(List.of(accounts));
    }

    private void stubWrites() {
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(inv -> {
            JournalEntry entry = inv.getArgument(0);
            entry.setId(50L);
            return entry;
        });
        // Only reached when the balances could be applied
        lenient().when(postingRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static JournalEntryRequest entry(PostingRequest... lines) {
        return new JournalEntryRequest(JournalEntryType.TRANSFER, "test", "transfer", 7L, List.of(lines));
    }

    private static PostingRequest line(Long accountId, PostingDirection direction, long amount) {
        return new PostingRequest(accountId, direction, amount, "USD");
    }

    private static Account systemAccount(Long id, SystemAccountKind kind) {
        Account account = account(id, "USD");
        account.setType(AccountType.SYSTEM);
        account.setSystemKind(kind);
        return account;
    }
}
