package com.waller.wallet_platform.ledger.service;

import static com.waller.wallet_platform.support.TestData.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.AccountType;
import com.waller.wallet_platform.account.entity.SystemAccountKind;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.ledger.dto.JournalEntryRequest;
import com.waller.wallet_platform.ledger.dto.PostingRequest;
import com.waller.wallet_platform.ledger.entity.JournalEntry;
import com.waller.wallet_platform.ledger.entity.JournalEntryType;
import com.waller.wallet_platform.ledger.entity.Posting;
import com.waller.wallet_platform.ledger.entity.PostingDirection;
import com.waller.wallet_platform.ledger.mapper.JournalEntryMapper;
import com.waller.wallet_platform.ledger.mapper.PostingMapper;
import com.waller.wallet_platform.ledger.repository.JournalEntryRepository;
import com.waller.wallet_platform.ledger.repository.PostingRepository;

// Property-based sketch: the ledger's invariants hold after any sequence of generated entries, accepted or refused.
// The book is in memory (the same mocks as LedgerServiceImplTest), so this checks LedgerServiceImpl's logic, not the
// schema; LedgerIntegrityTest covers the database side.
//
// Plain JUnit, one run per seed: jqwik (the usual library) is built on JUnit Platform 1.x and breaks discovery under
// Spring Boot 4's JUnit 6. A failing run's name is its seed; rerun it with -Dledger.seed=<seed>.
// TODO: move to jqwik (or similar) once it supports JUnit 6: generators with shrinking instead of a raw Random.
@ExtendWith(MockitoExtension.class)
class LedgerInvariantsPropertyTest {

    private static final int RUNS = 200;
    private static final int ENTRIES_PER_RUN = 50;
    private static final List<String> CURRENCIES = List.of("USD", "EUR");

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

    // The in-memory book: every account by id, and every posting the ledger has written
    private final Map<Long, Account> accounts = new HashMap<>();
    private final List<Posting> written = new ArrayList<>();

    static LongStream seeds() {
        String fixed = System.getProperty("ledger.seed");
        if (fixed != null) {
            return LongStream.of(Long.parseLong(fixed));
        }
        long base = new Random().nextLong();
        return LongStream.range(0, RUNS).map(i -> base + i);
    }

    @BeforeEach
    void setUp() {
        // One funding system account per currency (it may go negative, like BANK_CLEARING), a fee account,
        // and a few users. Every balance starts at 0, so each currency's balances sum to 0.
        // TODO: start from arbitrary (still zero-sum) balances, not only from an empty book.
        add(systemAccount(1L, "USD", SystemAccountKind.BANK_CLEARING));
        add(systemAccount(2L, "USD", SystemAccountKind.FEES));
        add(systemAccount(3L, "EUR", SystemAccountKind.BANK_CLEARING));
        add(account(10L, "USD"));
        add(account(11L, "USD"));
        add(account(12L, "USD"));
        add(account(20L, "EUR"));
        add(account(21L, "EUR"));

        // The locking query returns the requested, existing accounts: the managed rows the service changes in place
        lenient().when(accountRepository.findAllByIdForUpdateInLockOrder(anyCollection(), eq(AccountType.SYSTEM)))
                .thenAnswer(inv -> inv.<Collection<Long>>getArgument(0).stream()
                        .map(accounts::get).filter(a -> a != null).toList());
        lenient().when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(postingRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Posting> postings = inv.getArgument(0);
            written.addAll(postings);
            return postings;
        });
    }

    // Any sequence of balanced entries: whether each is accepted or refused, the book stays consistent
    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void anySequenceOfEntries_keepsTheBookConsistent(long seed) {
        Random random = new Random(seed);
        for (int i = 0; i < ENTRIES_PER_RUN; i++) {
            JournalEntryRequest entry = balancedEntry(random);
            Map<Long, Long> before = balances();
            int writtenBefore = written.size();

            boolean accepted = post(entry);

            if (!accepted) {
                // Invariant: a refused entry changes nothing, and writes nothing
                assertThat(balances()).as("balances after a refused entry").isEqualTo(before);
                assertThat(written).as("postings after a refused entry").hasSize(writtenBefore);
            }
            assertInvariants();
        }
    }

    // An entry that does not balance in some currency is refused, whatever else it contains
    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void unbalancedEntry_isAlwaysRefused(long seed) {
        Random random = new Random(seed);
        JournalEntryRequest entry = balancedEntry(random);
        List<PostingRequest> lines = new ArrayList<>(entry.getPostings());
        PostingRequest skewed = lines.get(random.nextInt(lines.size()));
        lines.set(lines.indexOf(skewed), line(skewed.getAccountId(), skewed.getDirection(),
                skewed.getAmount() + 1 + random.nextInt(1_000), skewed.getCurrency()));
        entry.setPostings(lines);
        Map<Long, Long> before = balances();

        assertThatThrownBy(() -> ledgerService.post(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not balanced");
        assertThat(balances()).isEqualTo(before);
        assertThat(written).isEmpty();
    }

    // TODO: order doesn't matter: the same postings in any order give the same balances (shuffle the lines).
    // TODO: reversal: posting an entry and then its mirror (DEBIT <-> CREDIT) restores every balance exactly.
    // TODO: split/merge: one line of 100 and two lines of 60 + 40 to the same account give the same balances.
    // TODO: a refused entry (insufficient funds) is refused by the user's balance alone: topping that user up by
    //  the shortfall makes the same entry succeed.
    // TODO: overflow: amounts near Long.MAX_VALUE are refused (Math.addExact) rather than wrapping a balance.
    // TODO: one entry per (referenceType, referenceId, entryType). That's a unique index, so it belongs in a
    //  property test against Postgres (IntegrationTest), not here.
    // TODO: the same properties over the domain services (transfer, hold capture/release, deposit), e.g.
    //  a hold's capture + release never exceed the held amount, a transfer's total is conserved including the fee.
    // TODO: pitest runs this class too (targetTests is ledger.service.*), with a random base seed per run. Pin the
    //  seed there (-Dledger.seed) or lower RUNS, so mutation results are reproducible and not slow.

    private void assertInvariants() {
        // Invariant: money is neither created nor destroyed. In each currency the balances sum to 0.
        Map<String, Long> totals = new HashMap<>();
        accounts.values().forEach(a -> totals.merge(a.getCurrency(), a.getBalance(), Math::addExact));
        assertThat(totals.values()).as("sum of balances per currency").allMatch(total -> total == 0L);

        // Invariant: only system accounts go negative
        assertThat(accounts.values())
                .filteredOn(a -> a.getType() != AccountType.SYSTEM)
                .as("user balances")
                .allMatch(a -> a.getBalance() >= 0);

        // Invariant: every balance is the replay of its postings (credits minus debits)
        Map<Long, Long> replayed = new HashMap<>();
        accounts.keySet().forEach(id -> replayed.put(id, 0L));
        written.forEach(p -> replayed.merge(p.getAccountId(),
                p.getDirection() == PostingDirection.CREDIT ? p.getAmount() : -p.getAmount(), Math::addExact));
        assertThat(balances()).as("balances vs. replayed postings").isEqualTo(replayed);

        // Invariant: each posting's currency is its account's currency
        assertThat(written).allMatch(p -> p.getCurrency().equals(accounts.get(p.getAccountId()).getCurrency()));
    }

    // true if written, false if refused for insufficient funds; anything else is a bug in the ledger or generator
    private boolean post(JournalEntryRequest entry) {
        try {
            ledgerService.post(entry);
            return true;
        } catch (ResponseStatusException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            return false;
        }
    }

    // --- Generators ---

    // Balanced in one currency: 1-3 debit lines, their total split over 1-3 credit lines, on that currency's accounts
    // TODO: entries spanning two currencies (each balanced on its own), and lines that hit the same account twice.
    // TODO: bias amounts towards the edges: 1, a user's exact balance, balance + 1. Today an off-by-one in the
    //  insufficient-funds check (< -1 instead of < 0) is caught by about 1 seed in 200.
    private JournalEntryRequest balancedEntry(Random random) {
        String currency = CURRENCIES.get(random.nextInt(CURRENCIES.size()));
        List<Long> pool = accounts.values().stream()
                .filter(a -> a.getCurrency().equals(currency)).map(Account::getId).sorted().toList();
        List<PostingRequest> lines = new ArrayList<>();
        long total = 0;
        for (int i = 0, n = 1 + random.nextInt(3); i < n; i++) {
            long amount = 1 + random.nextInt(10_000);
            total += amount;
            lines.add(line(pick(pool, random), PostingDirection.DEBIT, amount, currency));
        }
        // No more credit lines than units to split, or a line would get 0 (or less), which the ledger rightly refuses
        int credits = (int) Math.min(1 + random.nextInt(3), total);
        for (int i = 0; i < credits; i++) {
            // The last credit takes the rest; the others take a share, but at least 1 is left for each remaining one
            long amount = i == credits - 1 ? total : 1 + (long) (random.nextDouble() * (total - (credits - i)));
            total -= amount;
            lines.add(line(pick(pool, random), PostingDirection.CREDIT, amount, currency));
        }
        JournalEntryType type = JournalEntryType.values()[random.nextInt(JournalEntryType.values().length)];
        return new JournalEntryRequest(type, "generated", "property", random.nextLong(1, Long.MAX_VALUE), lines);
    }

    private static long pick(List<Long> pool, Random random) {
        return pool.get(random.nextInt(pool.size()));
    }

    private static PostingRequest line(long accountId, PostingDirection direction, long amount, String currency) {
        return new PostingRequest(accountId, direction, amount, currency);
    }

    private Map<Long, Long> balances() {
        Map<Long, Long> balances = new HashMap<>();
        accounts.values().forEach(a -> balances.put(a.getId(), a.getBalance()));
        return balances;
    }

    private void add(Account account) {
        account.setBalance(0L);
        accounts.put(account.getId(), account);
    }

    private static Account systemAccount(Long id, String currency, SystemAccountKind kind) {
        Account account = account(id, currency);
        account.setType(AccountType.SYSTEM);
        account.setSystemKind(kind);
        return account;
    }
}
