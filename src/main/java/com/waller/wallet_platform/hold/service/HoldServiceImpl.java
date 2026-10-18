package com.waller.wallet_platform.hold.service;

import com.waller.wallet_platform.outbox.service.OutboxEventService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.AccountStatus;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.security.AccountAccessChecker;
import com.waller.wallet_platform.common.util.ApiUtils;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import com.waller.wallet_platform.hold.mapper.HoldMapper;
import com.waller.wallet_platform.hold.repository.HoldRepository;
import com.waller.wallet_platform.hold.dto.HoldCaptureRequest;
import com.waller.wallet_platform.hold.dto.HoldDto;
import com.waller.wallet_platform.hold.dto.HoldRequest;
import com.waller.wallet_platform.hold.entity.Hold;
import com.waller.wallet_platform.ledger.dto.JournalEntryDto;
import com.waller.wallet_platform.ledger.dto.JournalEntryRequest;
import com.waller.wallet_platform.ledger.dto.PostingRequest;
import com.waller.wallet_platform.ledger.entity.JournalEntryType;
import com.waller.wallet_platform.ledger.entity.PostingDirection;
import com.waller.wallet_platform.ledger.repository.JournalEntryRepository;
import com.waller.wallet_platform.ledger.service.LedgerService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.waller.wallet_platform.hold.entity.HoldStatus.ACTIVE;
import static com.waller.wallet_platform.hold.entity.HoldStatus.CAPTURED;
import static com.waller.wallet_platform.hold.entity.HoldStatus.EXPIRED;
import static com.waller.wallet_platform.hold.entity.HoldStatus.RELEASED;

@Service
@RequiredArgsConstructor
public class HoldServiceImpl implements HoldService {

    private static final String HOLD_NOT_FOUND = "Hold not found";
    private static final String ACCOUNT_NOT_FOUND = "Account not found";
    private static final String AGGREGATE_TYPE = "hold";
    private static final String HOLD_CREATED = "HoldCreated";
    private static final String HOLD_CAPTURED = "HoldCaptured";
    private static final String HOLD_RELEASED = "HoldReleased";
    private static final String HOLD_EXPIRED = "HoldExpired";

    private final HoldRepository holdRepository;
    private final AccountRepository accountRepository;
    private final HoldMapper holdMapper;
    private final AccountAccessChecker accountAccessChecker;
    private final OutboxEventService outboxEventService;
    private final LedgerService ledgerService;
    private final JournalEntryRepository journalEntryRepository;
    private final AppUserRepository userRepository;

    // A merchant may reserve a customer's money for at most this long
    @Value("${hold.max-ttl:P7D}")
    private Duration maxTtl = Duration.ofDays(7);

    // Merchant-only (enforced in the controller): the account belongs to the customer, not the caller.
    // The caller is recorded as the hold's merchant, the only one who may capture or release it.
    @Override
    @Transactional
    public HoldDto createHold(HoldRequest request) {
        AppUser merchant = currentUser()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        Account account = accountRepository.findUserAccountByIdForUpdate(request.getAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ACCOUNT_NOT_FOUND));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account is not active");
        }

        if (!account.getCurrency().equals(request.getCurrency())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Hold currency does not match account currency " + account.getCurrency());
        }

        if (request.getExpiresAt().isAfter(Instant.now().plus(maxTtl))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "expiresAt can be at most " + maxTtl.toHours() + " hours from now");
        }

        Hold hold = new Hold();
        hold.setAccount(account);
        hold.setAmount(request.getAmount());
        hold.setCurrency(request.getCurrency());
        hold.setExpiresAt(request.getExpiresAt());
        hold.setMerchant(merchant);

        long held = holdRepository.sumAmountByAccountIdAndStatus(account.getId(), ACTIVE, Instant.now());
        if (account.getBalance() - held < hold.getAmount()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "There is not enough money on the account");
        }

        Hold saved = holdRepository.save(hold);
        HoldDto dto = holdMapper.toDto(saved);
        outboxEventService.record(AGGREGATE_TYPE, saved.getId(), HOLD_CREATED, dto);
        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public HoldDto getHold(Long id) {
        // Visible to the account's owner, admins and the merchant who created it. Anyone else's hold looks the
        // same as a missing one, so ids can't be probed
        Hold hold = holdRepository.findWithAccountById(id)
                .filter(h -> accountAccessChecker.canAccess(h.getAccount()) || isCurrentMerchant(h))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, HOLD_NOT_FOUND));
        return holdMapper.toDto(hold);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<HoldDto> getAccountHolds(Long accountId, int page, int size) {
        if (!accountAccessChecker.canAccess(accountId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ACCOUNT_NOT_FOUND);
        }
        return PageResponse.of(
                holdRepository.findByAccountIdOrderByCreatedAtDescIdDesc(accountId, PageRequest.of(page, size)),
                holdMapper::toDto);
    }

    // Only the hold's own merchant, and only into one of that merchant's own accounts. Moves the held amount there
    // through a HOLD_CAPTURE journal entry; a repeat returns the already captured hold.
    @Override
    @Transactional
    public HoldDto captureHold(Long id, HoldCaptureRequest request) {
        Hold hold = lockOwnHold(id);
        if (hold.getStatus() == CAPTURED) {
            return holdMapper.toDto(hold);
        }
        requireActive(hold, "captured");
        if (!hold.getExpiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold has expired");
        }

        Long sourceId = hold.getAccount().getId();
        Long destinationId = request.getDestinationAccountId();
        if (destinationId.equals(sourceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hold can not be captured to its own account");
        }
        // Both rows locked, lower id first like transfers, so a capture waits for (rather than fails against) a
        // concurrent transfer or capture on the same accounts, and the two can't deadlock
        Account first = lockAccount(Math.min(sourceId, destinationId));
        Account second = lockAccount(Math.max(sourceId, destinationId));
        Account source = first.getId().equals(sourceId) ? first : second;
        Account destination = first.getId().equals(sourceId) ? second : first;
        // Anyone else's account answers like a missing one, so a merchant can't route a capture to a third party
        if (!destination.getUser().getId().equals(hold.getMerchant().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ACCOUNT_NOT_FOUND);
        }
        if (destination.getStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Destination account is not active");
        }
        if (!destination.getCurrency().equals(hold.getCurrency())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Hold currency does not match destination account currency " + destination.getCurrency());
        }

        JournalEntryDto entry = ledgerService.post(new JournalEntryRequest(
                JournalEntryType.HOLD_CAPTURE,
                "Capture of hold " + hold.getId(),
                AGGREGATE_TYPE,
                hold.getId(),
                List.of(
                        new PostingRequest(source.getId(), PostingDirection.DEBIT, hold.getAmount(), hold.getCurrency()),
                        new PostingRequest(destination.getId(), PostingDirection.CREDIT, hold.getAmount(), hold.getCurrency()))));

        hold.setStatus(CAPTURED);
        hold.setCapturedEntry(journalEntryRepository.getReferenceById(entry.id()));
        return transitioned(hold, HOLD_CAPTURED);
    }

    // Only the hold's own merchant: gives the reserved funds back, e.g. on a cancelled order
    @Override
    @Transactional
    public HoldDto releaseHold(Long id) {
        Hold hold = lockOwnHold(id);
        if (hold.getStatus() == RELEASED) {
            return holdMapper.toDto(hold);
        }
        requireActive(hold, "released");

        hold.setStatus(RELEASED);
        hold.setReleasedAt(Instant.now());
        return transitioned(hold, HOLD_RELEASED);
    }

    // Only once expiresAt has passed; until then the hold still reserves its funds. Called by HoldExpiryJob.
    @Override
    @Transactional
    public HoldDto expireHold(Long id) {
        Hold hold = lockHold(id);
        if (hold.getStatus() == EXPIRED) {
            return holdMapper.toDto(hold);
        }
        requireActive(hold, "expired");
        if (hold.getExpiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold has not expired yet");
        }

        hold.setStatus(EXPIRED);
        return transitioned(hold, HOLD_EXPIRED);
    }

    // The lock serializes concurrent transitions, so the status check below can't go stale
    private Hold lockHold(Long id) {
        return holdRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, HOLD_NOT_FOUND));
    }

    // System accounts are filtered out, so they answer like a missing account
    private Account lockAccount(Long id) {
        return accountRepository.findUserAccountByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ACCOUNT_NOT_FOUND));
    }

    // Another merchant's hold gets the same 404 as a missing one, even for a repeated transition
    private Hold lockOwnHold(Long id) {
        Hold hold = lockHold(id);
        if (!isCurrentMerchant(hold)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, HOLD_NOT_FOUND);
        }
        return hold;
    }

    // The principal's name is the user's email (see AppUserDetailsService)
    private Optional<AppUser> currentUser() {
        return userRepository.findByEmailIgnoreCase(ApiUtils.getCurrentUsername());
    }

    // Holds created before V10 have no merchant, so nobody matches them
    private boolean isCurrentMerchant(Hold hold) {
        return hold.getMerchant() != null && currentUser()
                .map(user -> user.getId().equals(hold.getMerchant().getId()))
                .orElse(false);
    }

    // Any other final state means the hold went a different way; that is a conflict, not a retry
    private void requireActive(Hold hold, String action) {
        if (hold.getStatus() != ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Hold is " + hold.getStatus() + " and can not be " + action);
        }
    }

    // Saved and flushed first so the event carries the new updatedAt
    private HoldDto transitioned(Hold hold, String eventType) {
        HoldDto dto = holdMapper.toDto(holdRepository.saveAndFlush(hold));
        outboxEventService.record(AGGREGATE_TYPE, hold.getId(), eventType, dto);
        return dto;
    }

}
