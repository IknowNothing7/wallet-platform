package com.waller.wallet_platform.transfer.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.AccountStatus;
import com.waller.wallet_platform.account.entity.SystemAccountKind;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.account.service.SystemAccountService;
import com.waller.wallet_platform.hold.entity.HoldStatus;
import com.waller.wallet_platform.hold.repository.HoldRepository;
import com.waller.wallet_platform.ledger.dto.JournalEntryDto;
import com.waller.wallet_platform.ledger.dto.JournalEntryRequest;
import com.waller.wallet_platform.ledger.dto.PostingRequest;
import com.waller.wallet_platform.ledger.entity.JournalEntryType;
import com.waller.wallet_platform.ledger.entity.PostingDirection;
import com.waller.wallet_platform.ledger.repository.JournalEntryRepository;
import com.waller.wallet_platform.ledger.service.JournalEntryService;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.transfer.dto.TransferDto;
import com.waller.wallet_platform.transfer.entity.Transfer;
import com.waller.wallet_platform.transfer.entity.TransferLimit;
import com.waller.wallet_platform.transfer.entity.TransferStatus;
import com.waller.wallet_platform.transfer.mapper.TransferMapper;
import com.waller.wallet_platform.transfer.repository.TransferLimitRepository;
import com.waller.wallet_platform.transfer.repository.TransferRepository;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransferProcessingServiceImpl implements TransferProcessingService {

    private static final String TRANSFER_NOT_FOUND = "Transfer not found";
    private static final String ACCOUNT_NOT_FOUND = "Account not found";
    private static final String AGGREGATE_TYPE = "transfer";
    private static final String TRANSFER_COMPLETED = "TransferCompleted";
    private static final String TRANSFER_FAILED = "TransferFailed";
    private static final String TRANSFER_REVERSED = "TransferReversed";
    private static final String INSUFFICIENT_FUNDS = "Insufficient funds";
    // 2%, rounded down in the user's favour, same as deposits
    private static final long FEE_BASIS_POINTS = 200;
    private static final int FAILURE_REASON_MAX_LENGTH = 255;

    private final TransferRepository transferRepository;
    private final TransferLimitRepository transferLimitRepository;
    private final TransferMapper transferMapper;
    private final AccountRepository accountRepository;
    private final HoldRepository holdRepository;
    private final JournalEntryService journalEntryService;
    private final JournalEntryRepository journalEntryRepository;
    private final OutboxEventService outboxEventService;
    private final SystemAccountService systemAccountService;
    private final TransferRetryPolicy retryPolicy;

    @Override
    @Transactional
    public TransferDto process(Long transferId) {
        Transfer transfer = lockTransfer(transferId);
        if (transfer.getStatus() != TransferStatus.PENDING) {
            return transferMapper.toDto(transfer);
        }

        Long fromId = transfer.getFromAccount().getId();
        Long toId = transfer.getToAccount().getId();
        Account first = lockAccount(Math.min(fromId, toId));
        Account second = lockAccount(Math.max(fromId, toId));
        Account from = first.getId().equals(fromId) ? first : second;
        Account to = first.getId().equals(toId) ? first : second;

        String rejection = rejectionReason(transfer, from, to);
        if (rejection != null) {
            return fail(transfer, rejection);
        }

        // The fee comes out of what the recipient gets: sender -> recipient + fees
        long fee = fee(transfer);
        List<PostingRequest> postings = new ArrayList<>();
        postings.add(new PostingRequest(from.getId(), PostingDirection.DEBIT, transfer.getAmount(), transfer.getCurrency()));
        postings.add(new PostingRequest(to.getId(), PostingDirection.CREDIT, transfer.getAmount() - fee, transfer.getCurrency()));
        // Postings must be positive, so small transfers whose fee rounds to 0 get no fee posting
        if (fee > 0) {
            Account fees = systemAccountService.get(SystemAccountKind.FEES, transfer.getCurrency());
            postings.add(new PostingRequest(fees.getId(), PostingDirection.CREDIT, fee, transfer.getCurrency()));
        }
        JournalEntryDto entry = journalEntryService.createJournalEntry(new JournalEntryRequest(
                JournalEntryType.TRANSFER,
                "Transfer " + transfer.getId(),
                AGGREGATE_TYPE,
                transfer.getId(),
                postings));

        transfer.setStatus(TransferStatus.COMPLETED);
        transfer.setJournalEntry(journalEntryRepository.getReferenceById(entry.id()));
        log.info("Transfer {} completed, fee {}, entry {}", transfer.getId(), fee, entry.id());
        return transitioned(transfer, TRANSFER_COMPLETED);
    }

    @Override
    @Transactional
    public TransferDto recordFailedAttempt(Long transferId, String error) {
        Transfer transfer = lockTransfer(transferId);
        if (transfer.getStatus() != TransferStatus.PENDING) {
            return transferMapper.toDto(transfer);
        }
        int attempts = transfer.getAttempts() + 1;
        transfer.setAttempts(attempts);
        if (retryPolicy.exhausted(attempts)) {
            transfer.setNextAttemptAt(null);
            log.error("Transfer {} could not be processed in {} attempts, marking FAILED: {}", transferId, attempts, error);
            return fail(transfer, truncate("Processing failed after " + attempts + " attempts: " + error));
        }
        transfer.setNextAttemptAt(retryPolicy.nextAttemptAt(attempts, Instant.now()));
        log.warn("Processing of transfer {} failed (attempt {}/{}), next try at {}: {}",
                transferId, attempts, retryPolicy.maxAttempts(), transfer.getNextAttemptAt(), error);
        return transferMapper.toDto(transferRepository.saveAndFlush(transfer));
    }

    // Undoes the whole entry, fee included: recipient + fees -> sender
    @Override
    @Transactional
    public TransferDto reverse(Long transferId, String reason) {
        Transfer transfer = lockTransfer(transferId);
        if (transfer.getStatus() == TransferStatus.REVERSED) {
            return transferMapper.toDto(transfer);
        }
        if (transfer.getStatus() != TransferStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Transfer is " + transfer.getStatus() + " and can not be reversed");
        }

        // Same id order as process(), so a reversal and a transfer between the same accounts can't deadlock
        Long fromId = transfer.getFromAccount().getId();
        Long toId = transfer.getToAccount().getId();
        Account first = lockAccount(Math.min(fromId, toId));
        Account second = lockAccount(Math.max(fromId, toId));
        Account from = first.getId().equals(fromId) ? first : second;
        Account to = first.getId().equals(toId) ? first : second;

        // Refused rather than going negative; the transfer stays COMPLETED (it was booked, so it isn't FAILED)
        long fee = fee(transfer);
        long received = transfer.getAmount() - fee;
        long held = holdRepository.sumAmountByAccountIdAndStatus(to.getId(), HoldStatus.ACTIVE, Instant.now());
        if (to.getBalance() - held < received) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Destination account no longer has the funds to reverse this transfer");
        }

        List<PostingRequest> postings = new ArrayList<>();
        postings.add(new PostingRequest(to.getId(), PostingDirection.DEBIT, received, transfer.getCurrency()));
        if (fee > 0) {
            Account fees = systemAccountService.get(SystemAccountKind.FEES, transfer.getCurrency());
            postings.add(new PostingRequest(fees.getId(), PostingDirection.DEBIT, fee, transfer.getCurrency()));
        }
        postings.add(new PostingRequest(from.getId(), PostingDirection.CREDIT, transfer.getAmount(), transfer.getCurrency()));
        JournalEntryDto entry = journalEntryService.createJournalEntry(new JournalEntryRequest(
                JournalEntryType.REVERSAL,
                "Reversal of transfer " + transfer.getId() + (reason == null ? "" : ": " + reason),
                AGGREGATE_TYPE,
                transfer.getId(),
                postings));

        // journalEntry keeps pointing at the original entry; the reversal is found by reference ("transfer", id)
        transfer.setStatus(TransferStatus.REVERSED);
        log.info("Transfer {} reversed, entry {}", transfer.getId(), entry.id());
        return transitioned(transfer, TRANSFER_REVERSED);
    }

    // null when the transfer can be booked
    private String rejectionReason(Transfer transfer, Account from, Account to) {
        if (from.getStatus() != AccountStatus.ACTIVE) {
            return "Source account is not active";
        }
        if (to.getStatus() != AccountStatus.ACTIVE) {
            return "Destination account is not active";
        }
        // Accounts can't change currency, but the transfer row could have been created with a stale check
        if (!from.getCurrency().equals(transfer.getCurrency()) || !to.getCurrency().equals(transfer.getCurrency())) {
            return "Transfer currency does not match the accounts";
        }
        // Money reserved by active holds is not available, same rule as HoldServiceImpl.createHold
        long held = holdRepository.sumAmountByAccountIdAndStatus(from.getId(), HoldStatus.ACTIVE, Instant.now());
        if (from.getBalance() - held < transfer.getAmount()) {
            return INSUFFICIENT_FUNDS;
        }
        return limitRejection(transfer, from);
    }

    private static long fee(Transfer transfer) {
        return transfer.getAmount() * FEE_BASIS_POINTS / 10_000;
    }

    private String limitRejection(Transfer transfer, Account from) {
        TransferLimit limit = transferLimitRepository.findByAccountId(from.getId())
                .or(() -> transferLimitRepository.findByCurrency(transfer.getCurrency()))
                .orElse(null);
        if (limit == null) {
            return null;
        }
        if (limit.getPerTransferMax() != null && transfer.getAmount() > limit.getPerTransferMax()) {
            return "Amount exceeds the per-transfer limit";
        }
        if (limit.getDailyMax() != null) {
            long sentToday = transferRepository.sumAmountByFromAccountIdAndStatusSince(
                    from.getId(), TransferStatus.COMPLETED, Instant.now().minus(Duration.ofDays(1)));
            if (sentToday + transfer.getAmount() > limit.getDailyMax()) {
                return "Amount exceeds the daily transfer limit";
            }
        }
        return null;
    }

    private static String truncate(String reason) {
        return reason.length() <= FAILURE_REASON_MAX_LENGTH ? reason : reason.substring(0, FAILURE_REASON_MAX_LENGTH);
    }

    private TransferDto fail(Transfer transfer, String reason) {
        transfer.setStatus(TransferStatus.FAILED);
        transfer.setFailureReason(reason);
        log.info("Transfer {} failed: {}", transfer.getId(), reason);
        return transitioned(transfer, TRANSFER_FAILED);
    }

    // The lock serializes the job and a direct call, so the status check above can't go stale
    private Transfer lockTransfer(Long id) {
        return transferRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, TRANSFER_NOT_FOUND));
    }

    private Account lockAccount(Long id) {
        return accountRepository.findUserAccountByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ACCOUNT_NOT_FOUND));
    }

    // Saved and flushed first so the event carries the new updatedAt
    private TransferDto transitioned(Transfer transfer, String eventType) {
        TransferDto dto = transferMapper.toDto(transferRepository.saveAndFlush(transfer));
        outboxEventService.record(AGGREGATE_TYPE, transfer.getId(), eventType, dto);
        return dto;
    }
}
