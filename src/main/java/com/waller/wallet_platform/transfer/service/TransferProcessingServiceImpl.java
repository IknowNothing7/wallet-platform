package com.waller.wallet_platform.transfer.service;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.AccountStatus;
import com.waller.wallet_platform.account.repository.AccountRepository;
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
import com.waller.wallet_platform.transfer.entity.TransferStatus;
import com.waller.wallet_platform.transfer.mapper.TransferMapper;
import com.waller.wallet_platform.transfer.repository.TransferRepository;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransferProcessingServiceImpl implements TransferProcessingService {

    private static final String TRANSFER_NOT_FOUND = "Transfer not found";
    private static final String ACCOUNT_NOT_FOUND = "Account not found";
    private static final String AGGREGATE_TYPE = "transfer";
    // TODO: Add contracts/events/TransferCompleted|TransferFailed|TransferReversed.v1.schema.json and the README rows
    private static final String TRANSFER_COMPLETED = "TransferCompleted";
    private static final String TRANSFER_FAILED = "TransferFailed";
    private static final String TRANSFER_REVERSED = "TransferReversed";
    private static final String INSUFFICIENT_FUNDS = "Insufficient funds";

    private final TransferRepository transferRepository;
    private final TransferMapper transferMapper;
    private final AccountRepository accountRepository;
    private final HoldRepository holdRepository;
    private final JournalEntryService journalEntryService;
    private final JournalEntryRepository journalEntryRepository;
    private final OutboxEventService outboxEventService;

    // TODO: Wire this in. Either call it from createTransfer in the same transaction (synchronous: the response
    //  already says COMPLETED/FAILED), or leave createTransfer as is and let TransferProcessingJob pick it up
    //  (asynchronous: the client polls GET /transfer/{id}). Synchronous is simpler; async allows limits/fraud checks.
    // TODO: Idempotency-Key on POST /transfer/create (common/idempotency exists), so a retried request
    //  doesn't create and book a second transfer
    @Override
    @Transactional
    public TransferDto process(Long transferId) {
        Transfer transfer = lockTransfer(transferId);
        if (transfer.getStatus() != TransferStatus.PENDING) {
            return transferMapper.toDto(transfer);
        }

        // Always lock in id order: A->B and B->A at the same time would otherwise deadlock
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

        JournalEntryDto entry = journalEntryService.createJournalEntry(new JournalEntryRequest(
                JournalEntryType.TRANSFER,
                "Transfer " + transfer.getId(),
                AGGREGATE_TYPE,
                transfer.getId(),
                List.of(
                        new PostingRequest(from.getId(), PostingDirection.DEBIT, transfer.getAmount(), transfer.getCurrency()),
                        new PostingRequest(to.getId(), PostingDirection.CREDIT, transfer.getAmount(), transfer.getCurrency()))));
        // TODO: Balances only change once PostingServiceImpl applies postings (its own TODO); until then the
        //  funds check above reads a balance that never moves
        // TODO: Fees: a FEE posting from the sender to SystemAccountKind.FEES

        transfer.setStatus(TransferStatus.COMPLETED);
        transfer.setJournalEntry(journalEntryRepository.getReferenceById(entry.id()));
        log.info("Transfer {} completed, entry {}", transfer.getId(), entry.id());
        return transitioned(transfer, TRANSFER_COMPLETED);
    }

    // TODO: Expose as an admin-only endpoint (POST /transfer/{id}/reverse), like the journal entry controller
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

        // TODO: Lock both accounts in id order as in process(), and decide what happens when the recipient
        //  has already spent the money: refuse (409), or allow a negative balance and collect it later
        // TODO: Partial reversals (amount < transfer amount) would need a reversed_amount column
        Account from = transfer.getFromAccount();
        Account to = transfer.getToAccount();
        JournalEntryDto entry = journalEntryService.createJournalEntry(new JournalEntryRequest(
                JournalEntryType.REVERSAL,
                "Reversal of transfer " + transfer.getId() + (reason == null ? "" : ": " + reason),
                AGGREGATE_TYPE,
                transfer.getId(),
                List.of(
                        new PostingRequest(to.getId(), PostingDirection.DEBIT, transfer.getAmount(), transfer.getCurrency()),
                        new PostingRequest(from.getId(), PostingDirection.CREDIT, transfer.getAmount(), transfer.getCurrency()))));

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
        // TODO: Limits (per transfer, daily per user) and a fraud/velocity check
        return null;
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
