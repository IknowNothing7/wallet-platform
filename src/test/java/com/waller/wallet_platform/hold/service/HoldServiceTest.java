package com.waller.wallet_platform.hold.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.AccountStatus;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.hold.dto.HoldCaptureRequest;
import com.waller.wallet_platform.hold.dto.HoldDto;
import com.waller.wallet_platform.hold.dto.HoldRequest;
import com.waller.wallet_platform.hold.entity.Hold;
import com.waller.wallet_platform.hold.entity.HoldStatus;
import com.waller.wallet_platform.hold.mapper.HoldMapper;
import com.waller.wallet_platform.hold.repository.HoldRepository;
import com.waller.wallet_platform.ledger.dto.JournalEntryDto;
import com.waller.wallet_platform.ledger.dto.JournalEntryRequest;
import com.waller.wallet_platform.ledger.dto.PostingRequest;
import com.waller.wallet_platform.ledger.entity.JournalEntry;
import com.waller.wallet_platform.ledger.entity.JournalEntryType;
import com.waller.wallet_platform.ledger.entity.PostingDirection;
import com.waller.wallet_platform.ledger.repository.JournalEntryRepository;
import com.waller.wallet_platform.ledger.service.JournalEntryService;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.security.AccountAccessChecker;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static com.waller.wallet_platform.support.TestData.hold;

@ExtendWith(MockitoExtension.class)
public class HoldServiceTest {

    @Mock
    private HoldRepository holdRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private HoldMapper holdMapper;

    @Mock
    private AccountAccessChecker accountAccessChecker;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private JournalEntryService journalEntryService;

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private AppUserRepository userRepository;

    @InjectMocks
    private HoldServiceImpl holdService;

    private static final String MERCHANT_EMAIL = "merchant@example.com";
    private static final String OTHER_MERCHANT_EMAIL = "other-merchant@example.com";

    private AppUser merchant;
    private Account account;
    private Account destination;
    private Hold hold;
    private HoldDto holdDto;
    private HoldRequest request;

    @BeforeEach
    void setUp() {
        account = new Account();
        account.setId(1L);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCurrency("USD");
        account.setBalance(500L);

        destination = new Account();
        destination.setId(2L);
        destination.setStatus(AccountStatus.ACTIVE);
        destination.setCurrency("USD");

        merchant = new AppUser();
        merchant.setId(50L);
        merchant.setEmail(MERCHANT_EMAIL);
        AppUser otherMerchant = new AppUser();
        otherMerchant.setId(51L);
        otherMerchant.setEmail(OTHER_MERCHANT_EMAIL);
        // Not every test reaches the merchant lookup (e.g. expireHold, getAccountHolds)
        lenient().when(userRepository.findByEmailIgnoreCase(MERCHANT_EMAIL)).thenReturn(Optional.of(merchant));
        lenient().when(userRepository.findByEmailIgnoreCase(OTHER_MERCHANT_EMAIL)).thenReturn(Optional.of(otherMerchant));
        loginAs(MERCHANT_EMAIL);

        hold = hold(account, 100L, "USD", Instant.now().plus(Duration.ofHours(1)));
        hold.setId(10L);
        hold.setMerchant(merchant);

        holdDto = HoldDto.builder()
                .id(10L)
                .accountId(1L)
                .amount(100L)
                .currency("USD")
                .status(HoldStatus.ACTIVE)
                .build();

        request = new HoldRequest(1L, 100L, "USD", Instant.now().plus(Duration.ofHours(1)));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // The principal's name is the user's email (see AppUserDetailsService)
    private static void loginAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(email, null));
    }

    @Test
    void createHold_savesHoldAndRecordsEvent() {
        when(accountRepository.findUserAccountByIdForUpdate(1L)).thenReturn(Optional.of(account));
        when(holdRepository.sumAmountByAccountIdAndStatus(eq(1L), eq(HoldStatus.ACTIVE), any(Instant.class))).thenReturn(0L);
        when(holdRepository.save(any(Hold.class))).thenReturn(hold);
        when(holdMapper.toDto(hold)).thenReturn(holdDto);

        HoldDto result = holdService.createHold(request);

        assertThat(result).isEqualTo(holdDto);

        ArgumentCaptor<Hold> captor = ArgumentCaptor.forClass(Hold.class);
        verify(holdRepository).save(captor.capture());
        Hold saved = captor.getValue();
        assertThat(saved.getAccount()).isSameAs(account);
        assertThat(saved.getAmount()).isEqualTo(100L);
        assertThat(saved.getCurrency()).isEqualTo("USD");
        assertThat(saved.getExpiresAt()).isEqualTo(request.getExpiresAt());
        assertThat(saved.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(saved.getMerchant()).isSameAs(merchant);

        verify(outboxEventService).record("hold", 10L, "HoldCreated", holdDto);
    }

    @Test
    void createHold_unknownPrincipal_is401() {
        loginAs("nobody@example.com");

        assertThatThrownBy(() -> holdService.createHold(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verify(holdRepository, never()).save(any());
        verifyNoInteractions(outboxEventService);
    }

    @Test
    void createHold_allowsAmountEqualToAvailableBalance() {
        when(accountRepository.findUserAccountByIdForUpdate(1L)).thenReturn(Optional.of(account));
        when(holdRepository.sumAmountByAccountIdAndStatus(eq(1L), eq(HoldStatus.ACTIVE), any(Instant.class))).thenReturn(400L);
        when(holdRepository.save(any(Hold.class))).thenReturn(hold);
        when(holdMapper.toDto(hold)).thenReturn(holdDto);

        assertThat(holdService.createHold(request)).isEqualTo(holdDto);
    }

    // Holds past expiresAt are excluded by the query's "now"; any other Instant would count expired holds (or skip
    // live ones). The service reads the clock with Instant.now(), so "now" is bracketed by the test's own clock reads.
    @Test
    void createHold_sumsActiveHoldsAsOfNow() {
        when(accountRepository.findUserAccountByIdForUpdate(1L)).thenReturn(Optional.of(account));
        when(holdRepository.sumAmountByAccountIdAndStatus(eq(1L), eq(HoldStatus.ACTIVE), any(Instant.class))).thenReturn(0L);
        when(holdRepository.save(any(Hold.class))).thenReturn(hold);
        when(holdMapper.toDto(hold)).thenReturn(holdDto);

        Instant before = Instant.now();
        holdService.createHold(request);
        Instant after = Instant.now();

        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        verify(holdRepository).sumAmountByAccountIdAndStatus(eq(1L), eq(HoldStatus.ACTIVE), now.capture());
        assertThat(now.getValue()).isBetween(before, after);
    }

    @Test
    void createHold_accountNotFound_throws() {
        when(accountRepository.findUserAccountByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.createHold(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Account not found");

        verifyNoInteractions(holdRepository, outboxEventService);
    }

    @Test
    void createHold_accountNotActive_throws() {
        account.setStatus(AccountStatus.FROZEN);
        when(accountRepository.findUserAccountByIdForUpdate(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> holdService.createHold(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Account is not active");

        verifyNoInteractions(holdRepository, outboxEventService);
    }

    @Test
    void createHold_currencyMismatch_throws() {
        request.setCurrency("EUR");
        when(accountRepository.findUserAccountByIdForUpdate(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> holdService.createHold(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Hold currency does not match account currency USD");

        verifyNoInteractions(holdRepository, outboxEventService);
    }

    @Test
    void createHold_insufficientFunds_throws() {
        when(accountRepository.findUserAccountByIdForUpdate(1L)).thenReturn(Optional.of(account));
        when(holdRepository.sumAmountByAccountIdAndStatus(eq(1L), eq(HoldStatus.ACTIVE), any(Instant.class))).thenReturn(450L);

        assertThatThrownBy(() -> holdService.createHold(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("There is not enough money on the account");

        verify(holdRepository, never()).save(any());
        verifyNoInteractions(outboxEventService);
    }

    @Test
    void getHold_returnsDto() {
        when(holdRepository.findWithAccountById(10L)).thenReturn(Optional.of(hold));
        when(accountAccessChecker.canAccess(account)).thenReturn(true);
        when(holdMapper.toDto(hold)).thenReturn(holdDto);

        assertThat(holdService.getHold(10L)).isEqualTo(holdDto);
    }

    @Test
    void getHold_notFound_throws() {
        when(holdRepository.findWithAccountById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.getHold(10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Hold not found");
    }

    @Test
    void getHold_creatingMerchant_canReadIt() {
        when(holdRepository.findWithAccountById(10L)).thenReturn(Optional.of(hold));
        when(accountAccessChecker.canAccess(account)).thenReturn(false);
        when(holdMapper.toDto(hold)).thenReturn(holdDto);

        assertThat(holdService.getHold(10L)).isEqualTo(holdDto);
    }

    @Test
    void getHold_someoneElsesHold_looksLikeNotFound() {
        loginAs(OTHER_MERCHANT_EMAIL);
        when(holdRepository.findWithAccountById(10L)).thenReturn(Optional.of(hold));
        when(accountAccessChecker.canAccess(account)).thenReturn(false);

        assertThatThrownBy(() -> holdService.getHold(10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Hold not found");

        verifyNoInteractions(holdMapper);
    }

    @Test
    void getAccountHolds_returnsMappedPage() {
        Pageable pageable = PageRequest.of(1, 10);
        Page<Hold> holds = new PageImpl<>(List.of(hold), pageable, 11);
        when(accountAccessChecker.canAccess(1L)).thenReturn(true);
        when(holdRepository.findByAccountIdOrderByCreatedAtDescIdDesc(1L, pageable)).thenReturn(holds);
        when(holdMapper.toDto(hold)).thenReturn(holdDto);

        PageResponse<HoldDto> result = holdService.getAccountHolds(1L, 1, 10);

        assertThat(result.content()).containsExactly(holdDto);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalElements()).isEqualTo(11);
        assertThat(result.totalPages()).isEqualTo(2);
    }

    @Test
    void getAccountHolds_noAccess_throws() {
        when(accountAccessChecker.canAccess(1L)).thenReturn(false);

        assertThatThrownBy(() -> holdService.getAccountHolds(1L, 0, 10))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Account not found");

        verifyNoInteractions(holdRepository, holdMapper);
    }

    @Test
    void captureHold_postsJournalEntryAndMarksCaptured() {
        JournalEntryDto entryDto = JournalEntryDto.builder().id(99L).build();
        JournalEntry entryRef = new JournalEntry();
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));
        when(accountRepository.findUserAccountById(2L)).thenReturn(Optional.of(destination));
        when(journalEntryService.createJournalEntry(any(JournalEntryRequest.class))).thenReturn(entryDto);
        when(journalEntryRepository.getReferenceById(99L)).thenReturn(entryRef);
        when(holdRepository.saveAndFlush(hold)).thenReturn(hold);
        when(holdMapper.toDto(hold)).thenReturn(holdDto);

        HoldDto result = holdService.captureHold(10L, new HoldCaptureRequest(2L));

        assertThat(result).isEqualTo(holdDto);
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CAPTURED);
        assertThat(hold.getCapturedEntry()).isSameAs(entryRef);

        ArgumentCaptor<JournalEntryRequest> captor = ArgumentCaptor.forClass(JournalEntryRequest.class);
        verify(journalEntryService).createJournalEntry(captor.capture());
        JournalEntryRequest entry = captor.getValue();
        assertThat(entry.getEntryType()).isEqualTo(JournalEntryType.HOLD_CAPTURE);
        assertThat(entry.getReferenceType()).isEqualTo("hold");
        assertThat(entry.getReferenceId()).isEqualTo(10L);
        assertThat(entry.getPostings()).hasSize(2);

        PostingRequest debit = entry.getPostings().get(0);
        assertThat(debit.getAccountId()).isEqualTo(1L);
        assertThat(debit.getDirection()).isEqualTo(PostingDirection.DEBIT);
        assertThat(debit.getAmount()).isEqualTo(100L);
        assertThat(debit.getCurrency()).isEqualTo("USD");

        PostingRequest credit = entry.getPostings().get(1);
        assertThat(credit.getAccountId()).isEqualTo(2L);
        assertThat(credit.getDirection()).isEqualTo(PostingDirection.CREDIT);
        assertThat(credit.getAmount()).isEqualTo(100L);
        assertThat(credit.getCurrency()).isEqualTo("USD");

        verify(outboxEventService).record("hold", 10L, "HoldCaptured", holdDto);
    }

    // findUserAccountById filters out system accounts, so a system destination looks like a missing one
    @Test
    void captureHold_toSystemAccount_is404() {
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));
        when(accountRepository.findUserAccountById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.captureHold(10L, new HoldCaptureRequest(99L)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Account not found");

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        verifyNoInteractions(journalEntryService, outboxEventService);
    }

    @Test
    void captureHold_alreadyCaptured_isIdempotent() {
        hold.setStatus(HoldStatus.CAPTURED);
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));
        when(holdMapper.toDto(hold)).thenReturn(holdDto);

        assertThat(holdService.captureHold(10L, new HoldCaptureRequest(2L))).isEqualTo(holdDto);

        verifyNoInteractions(journalEntryService, outboxEventService);
        verify(holdRepository, never()).saveAndFlush(any());
    }

    @Test
    void captureHold_notFound_throws() {
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.captureHold(10L, new HoldCaptureRequest(2L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Hold not found");
    }

    @Test
    void captureHold_releasedHold_throwsConflict() {
        hold.setStatus(HoldStatus.RELEASED);
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> holdService.captureHold(10L, new HoldCaptureRequest(2L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Hold is RELEASED and can not be captured");

        verifyNoInteractions(journalEntryService, outboxEventService);
    }

    @Test
    void captureHold_expired_throws() {
        hold.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> holdService.captureHold(10L, new HoldCaptureRequest(2L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Hold has expired");

        verifyNoInteractions(journalEntryService, outboxEventService);
    }

    @Test
    void captureHold_destinationNotFound_throws() {
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));
        when(accountRepository.findUserAccountById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.captureHold(10L, new HoldCaptureRequest(2L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Account not found");

        verifyNoInteractions(journalEntryService, outboxEventService);
    }

    @Test
    void captureHold_toOwnAccount_throws() {
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));
        when(accountRepository.findUserAccountById(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> holdService.captureHold(10L, new HoldCaptureRequest(1L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Hold can not be captured to its own account");

        verifyNoInteractions(journalEntryService, outboxEventService);
    }

    @Test
    void captureHold_destinationNotActive_throws() {
        destination.setStatus(AccountStatus.FROZEN);
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));
        when(accountRepository.findUserAccountById(2L)).thenReturn(Optional.of(destination));

        assertThatThrownBy(() -> holdService.captureHold(10L, new HoldCaptureRequest(2L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Destination account is not active");

        verifyNoInteractions(journalEntryService, outboxEventService);
    }

    @Test
    void captureHold_destinationCurrencyMismatch_throws() {
        destination.setCurrency("EUR");
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));
        when(accountRepository.findUserAccountById(2L)).thenReturn(Optional.of(destination));

        assertThatThrownBy(() -> holdService.captureHold(10L, new HoldCaptureRequest(2L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Hold currency does not match destination account currency EUR");

        verifyNoInteractions(journalEntryService, outboxEventService);
    }

    @Test
    void releaseHold_marksReleasedAndRecordsEvent() {
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));
        when(holdRepository.saveAndFlush(hold)).thenReturn(hold);
        when(holdMapper.toDto(hold)).thenReturn(holdDto);

        assertThat(holdService.releaseHold(10L)).isEqualTo(holdDto);

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
        assertThat(hold.getReleasedAt()).isNotNull();
        verify(outboxEventService).record("hold", 10L, "HoldReleased", holdDto);
    }

    @Test
    void releaseHold_alreadyReleased_isIdempotent() {
        hold.setStatus(HoldStatus.RELEASED);
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));
        when(holdMapper.toDto(hold)).thenReturn(holdDto);

        assertThat(holdService.releaseHold(10L)).isEqualTo(holdDto);

        verify(holdRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxEventService);
    }

    @Test
    void releaseHold_capturedHold_throwsConflict() {
        hold.setStatus(HoldStatus.CAPTURED);
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> holdService.releaseHold(10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Hold is CAPTURED and can not be released");

        verifyNoInteractions(outboxEventService);
    }

    @Test
    void expireHold_pastExpiry_marksExpired() {
        hold.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));
        when(holdRepository.saveAndFlush(hold)).thenReturn(hold);
        when(holdMapper.toDto(hold)).thenReturn(holdDto);

        assertThat(holdService.expireHold(10L)).isEqualTo(holdDto);

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        verify(outboxEventService).record("hold", 10L, "HoldExpired", holdDto);
    }

    @Test
    void expireHold_notYetExpired_throws() {
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> holdService.expireHold(10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Hold has not expired yet");

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        verifyNoInteractions(outboxEventService);
    }

    @Test
    void expireHold_alreadyExpired_isIdempotent() {
        hold.setStatus(HoldStatus.EXPIRED);
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));
        when(holdMapper.toDto(hold)).thenReturn(holdDto);

        assertThat(holdService.expireHold(10L)).isEqualTo(holdDto);

        verify(holdRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxEventService);
    }

    @Test
    void expireHold_releasedHold_throwsConflict() {
        hold.setStatus(HoldStatus.RELEASED);
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> holdService.expireHold(10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Hold is RELEASED and can not be expired");

        verifyNoInteractions(outboxEventService);
    }

    @Test
    void releaseHold_notFound_is404() {
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.releaseHold(10L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Hold not found");

        verify(holdRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxEventService);
    }

    @Test
    void expireHold_notFound_is404() {
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.expireHold(10L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Hold not found");

        verify(holdRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxEventService);
    }

    // Another merchant's hold answers like a missing one, so hold ids can't be probed
    @Test
    void captureHold_anotherMerchantsHold_is404() {
        loginAs(OTHER_MERCHANT_EMAIL);
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> holdService.captureHold(10L, new HoldCaptureRequest(2L)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Hold not found");

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        verifyNoInteractions(journalEntryService, outboxEventService);
        verify(accountRepository, never()).findUserAccountById(any());
    }

    @Test
    void releaseHold_anotherMerchantsHold_is404() {
        loginAs(OTHER_MERCHANT_EMAIL);
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> holdService.releaseHold(10L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Hold not found");

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        verify(holdRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxEventService);
    }

    // Even a repeat of a finished transition must not tell another merchant the hold's state
    @Test
    void releaseHold_anotherMerchantsReleasedHold_is404() {
        loginAs(OTHER_MERCHANT_EMAIL);
        hold.setStatus(HoldStatus.RELEASED);
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> holdService.releaseHold(10L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verifyNoInteractions(holdMapper);
    }

    // Holds created before V10 have no recorded merchant; nobody may capture or release them, they just expire
    @Test
    void captureHold_holdWithoutMerchant_is404() {
        hold.setMerchant(null);
        when(holdRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> holdService.captureHold(10L, new HoldCaptureRequest(2L)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verifyNoInteractions(journalEntryService, outboxEventService);
    }
}
