package com.waller.wallet_platform.transfer.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
import com.waller.wallet_platform.ledger.service.LedgerService;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.transfer.dto.TransferDto;
import com.waller.wallet_platform.transfer.dto.TransferRequest;
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
    private static final String AGGREGATE_TYPE = "transfer";
    private static final String TRANSFER_COMPLETED = "TransferCompleted";
    private static final String TRANSFER_FAILED = "TransferFailed";
    private static final String TRANSFER_REVERSED = "TransferReversed";
    private static final String INSUFFICIENT_FUNDS = "Insufficient funds";
    // 2%, rounded down in the user's favour, same as deposits
    private static final long FEE_BASIS_POINTS = 200;

    private final TransferRepository transferRepository;
    private final TransferLimitRepository transferLimitRepository;
    private final TransferMapper transferMapper;
    private final AccountRepository accountRepository;
    private final HoldRepository holdRepository;
    private final LedgerService ledgerService;
    private final JournalEntryRepository journalEntryRepository;
    private final OutboxEventService outboxEventService;
    private final SystemAccountService systemAccountService;

    @Override
    @Transactional
    public TransferDto book(TransferRequest request) {
        Long fromId = request.getIdFromAccount();
        Long toId = request.getIdToAccount();
        if (Objects.equals(fromId, toId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can not transfer to the same account");
        }

        // Locked before anything reads them, so the balance and hold checks below see the latest committed state and
        // nothing can change it until this transaction ends. Concurrent transfers on the same account queue here.
        Locked accounts = lockInIdOrder(fromId, toId);
        Account from = accounts.from();
        Account to = accounts.to();
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Transfer currency does not match the target account currency " + to.getCurrency());
        }
        if (!from.getCurrency().equals(request.getCurrency())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Transfer currency does not match the account currency " + from.getCurrency());
        }

        Transfer transfer = new Transfer();
        transfer.setFromAccount(from);
        transfer.setToAccount(to);
        transfer.setAmount(request.getAmount());
        transfer.setCurrency(request.getCurrency());

        String rejection = rejectionReason(transfer, from, to);
        if (rejection != null) {
            transfer.setStatus(TransferStatus.FAILED);
            transfer.setFailureReason(rejection);
            TransferDto dto = saved(transfer, TRANSFER_FAILED);
            log.info("Transfer {} failed: {}", dto.id(), rejection);
            return dto;
        }

        // Saved first: the entry references the transfer's id
        transfer.setStatus(TransferStatus.COMPLETED);
        transferRepository.saveAndFlush(transfer);

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
        JournalEntryDto entry = ledgerService.post(new JournalEntryRequest(
                JournalEntryType.TRANSFER,
                "Transfer " + transfer.getId(),
                AGGREGATE_TYPE,
                transfer.getId(),
                postings));

        transfer.setJournalEntry(journalEntryRepository.getReferenceById(entry.id()));
        log.info("Transfer {} completed, fee {}, entry {}", transfer.getId(), fee, entry.id());
        return saved(transfer, TRANSFER_COMPLETED);
    }

    // Undoes the whole entry, fee included: recipient + fees -> sender
    @Override
    @Transactional
    public TransferDto reverse(Long transferId, String reason) {
        Transfer transfer = transferRepository.findByIdForUpdate(transferId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, TRANSFER_NOT_FOUND));
        if (transfer.getStatus() == TransferStatus.REVERSED) {
            return transferMapper.toDto(transfer);
        }
        if (transfer.getStatus() != TransferStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Transfer is " + transfer.getStatus() + " and can not be reversed");
        }

        // Same id order as book(), so a reversal and a transfer between the same accounts can't deadlock
        Locked accounts = lockInIdOrder(transfer.getFromAccount().getId(), transfer.getToAccount().getId());
        Account from = accounts.from();
        Account to = accounts.to();

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
        JournalEntryDto entry = ledgerService.post(new JournalEntryRequest(
                JournalEntryType.REVERSAL,
                "Reversal of transfer " + transfer.getId() + (reason == null ? "" : ": " + reason),
                AGGREGATE_TYPE,
                transfer.getId(),
                postings));

        // journalEntry keeps pointing at the original entry; the reversal is found by reference ("transfer", id)
        transfer.setStatus(TransferStatus.REVERSED);
        log.info("Transfer {} reversed, entry {}", transfer.getId(), entry.id());
        return saved(transfer, TRANSFER_REVERSED);
    }

    // null when the transfer can be booked
    private String rejectionReason(Transfer transfer, Account from, Account to) {
        if (from.getStatus() != AccountStatus.ACTIVE) {
            return "Source account is not active";
        }
        if (to.getStatus() != AccountStatus.ACTIVE) {
            return "Destination account is not active";
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

    // Exact under the source account's lock: every other booking from this account waits for it
    private String limitRejection(Transfer transfer, Account from) {
        TransferLimit limit = transferLimitRepository.findByAccountId(from.getId())
                .or(() -> transferLimitRepository.findByCurrency(transfer.getCurrency()))
                .orElse(null);
        // Fail closed: a currency is enabled for transfers by giving it a transfer_limit row (V5 seeds USD and EUR)
        if (limit == null) {
            return "Transfers in " + transfer.getCurrency() + " are not enabled";
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

    private record Locked(Account from, Account to) {
    }

    // Always lower id first, whichever way the money goes, so two transfers in opposite directions can't deadlock
    private Locked lockInIdOrder(Long fromId, Long toId) {
        Account first = lockAccount(Math.min(fromId, toId));
        Account second = lockAccount(Math.max(fromId, toId));
        return first.getId().equals(fromId) ? new Locked(first, second) : new Locked(second, first);
    }

    // System accounts are filtered out, so they answer like a missing account
    private Account lockAccount(Long id) {
        return accountRepository.findUserAccountByIdForUpdate(id).orElseThrow(() -> accountNotFound(id));
    }

    private static ResponseStatusException accountNotFound(Long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Account " + id + " not found");
    }

    // Saved and flushed first so the event carries the id and the new updatedAt
    private TransferDto saved(Transfer transfer, String eventType) {
        TransferDto dto = transferMapper.toDto(transferRepository.saveAndFlush(transfer));
        outboxEventService.record(AGGREGATE_TYPE, dto.id(), eventType, dto);
        return dto;
    }
}
