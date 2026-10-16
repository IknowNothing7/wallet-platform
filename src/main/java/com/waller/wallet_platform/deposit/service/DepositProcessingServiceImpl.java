package com.waller.wallet_platform.deposit.service;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.SystemAccountKind;
import com.waller.wallet_platform.account.service.SystemAccountService;
import com.waller.wallet_platform.bank.client.BankClient;
import com.waller.wallet_platform.bank.dto.BankPayment;
import com.waller.wallet_platform.bank.dto.BankPaymentRequest;
import com.waller.wallet_platform.bank.dto.BankPaymentResult;
import com.waller.wallet_platform.bank.dto.BankPaymentStatus;
import com.waller.wallet_platform.deposit.dto.DepositDto;
import com.waller.wallet_platform.deposit.entity.Deposit;
import com.waller.wallet_platform.deposit.entity.DepositStatus;
import com.waller.wallet_platform.deposit.mapper.DepositMapper;
import com.waller.wallet_platform.deposit.repository.DepositRepository;
import com.waller.wallet_platform.ledger.dto.JournalEntryDto;
import com.waller.wallet_platform.ledger.dto.JournalEntryRequest;
import com.waller.wallet_platform.ledger.dto.PostingRequest;
import com.waller.wallet_platform.ledger.entity.JournalEntryType;
import com.waller.wallet_platform.ledger.entity.PostingDirection;
import com.waller.wallet_platform.ledger.repository.JournalEntryRepository;
import com.waller.wallet_platform.ledger.service.JournalEntryService;
import com.waller.wallet_platform.outbox.service.OutboxEventService;

@Service
@Slf4j
@RequiredArgsConstructor
public class DepositProcessingServiceImpl implements DepositProcessingService {

    private static final String DEPOSIT_NOT_FOUND = "Deposit not found";
    private static final String AGGREGATE_TYPE = "deposit";
    // TODO: Add contracts/events/DepositSucceeded|DepositFailed|DepositExpired.v1.schema.json and the README table rows
    private static final String DEPOSIT_SUCCEEDED = "DepositSucceeded";
    private static final String DEPOSIT_FAILED = "DepositFailed";
    private static final String DEPOSIT_EXPIRED = "DepositExpired";
    private static final String IDEMPOTENCY_PREFIX = "deposit-";

    private final DepositRepository depositRepository;
    private final DepositMapper depositMapper;
    private final BankClient bankClient;
    private final SystemAccountService systemAccountService;
    private final JournalEntryService journalEntryService;
    private final JournalEntryRepository journalEntryRepository;
    private final OutboxEventService outboxEventService;
    private final TransactionTemplate transactionTemplate;

    // Deliberately not @Transactional: the bank call happens between two short transactions
    @Override
    public DepositDto submitToBank(Long depositId) {
        Deposit deposit = depositRepository.findById(depositId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, DEPOSIT_NOT_FOUND));
        if (deposit.getStatus() != DepositStatus.PENDING) {
            return depositMapper.toDto(deposit);
        }

        // TODO: Wire this in. createDeposit still takes gatewayReference from the client, but with bank-sim the
        //  reference only exists after POST /payments. Options: make gateway_reference nullable (migration V4,
        //  the unique constraint still works since NULLs don't collide), or keep the client-supplied value as
        //  our own reference and store bank-sim's in a new column. Then call submitToBank after createDeposit
        //  commits (directly, or from a DepositCreated consumer / outbox-style job so a crash in between is retried).
        // TODO: Set deposit.expiresAt on creation (config: deposit.pending-ttl) so expire() has something to check
        // TODO: Check the account is ACTIVE before sending money in (see the TODO in DepositServiceTest)
        BankPayment payment = bankClient.createPayment(
                IDEMPOTENCY_PREFIX + deposit.getId(),
                new BankPaymentRequest(deposit.getAmount(), deposit.getCurrency(), String.valueOf(deposit.getId())));
        log.info("Deposit {} submitted to bank as {}", deposit.getId(), payment.reference());

        return transactionTemplate.execute(status -> {
            Deposit locked = lockDeposit(depositId);
            // Set even if the webhook already arrived and settled it (bank-sim's delay is short)
            locked.setGatewayReference(payment.reference());
            return depositMapper.toDto(depositRepository.saveAndFlush(locked));
        });
    }

    @Override
    @Transactional
    public void applyResult(BankPaymentResult result) {
        Long depositId = parseDepositId(result.walletDepositId());
        if (depositId == null) {
            log.error("Bank result {} names an invalid deposit id {}", result.reference(), result.walletDepositId());
            return;
        }
        // Locked so a webhook, its duplicate and a reconciliation run can't settle the same deposit twice
        Deposit deposit = depositRepository.findByIdForUpdate(depositId).orElse(null);
        if (deposit == null) {
            // 2xx anyway: a retry would hit the same missing row. TODO: alert; this means money with no deposit.
            log.error("Bank result {} for unknown deposit {}", result.reference(), depositId);
            return;
        }

        if (!matches(deposit, result)) {
            // TODO: Alert / park for manual review. Never credit an amount the user didn't ask for.
            log.error("Bank result {} does not match deposit {} (amount/currency/reference)",
                    result.reference(), deposit.getId());
            return;
        }

        if (deposit.getStatus() != DepositStatus.PENDING) {
            if (deposit.getStatus() != toDepositStatus(result.status())) {
                // e.g. EXPIRED locally but the bank says SUCCEEDED: the money did arrive
                // TODO: Decide: credit anyway (late success) or refund through the bank; at least alert
                log.error("Deposit {} is {} but the bank now reports {}",
                        deposit.getId(), deposit.getStatus(), result.status());
            }
            // Otherwise a duplicate delivery (bank-sim's DUPLICATE scenario); nothing to do
            return;
        }

        switch (result.status()) {
            case SUCCEEDED -> succeed(deposit);
            case FAILED -> fail(deposit, result.failureReason());
            // A webhook is only sent for a final result; reconciliation filters PENDING out before calling
            case PENDING -> log.warn("Ignoring PENDING bank result for deposit {}", deposit.getId());
        }
    }

    @Override
    @Transactional
    public void expire(Long depositId) {
        Deposit deposit = lockDeposit(depositId);
        if (deposit.getStatus() != DepositStatus.PENDING
                || deposit.getExpiresAt() == null
                || deposit.getExpiresAt().isAfter(Instant.now())) {
            return;
        }
        // TODO: Ask the bank first (getPayment) so a payment that did succeed isn't expired; or cancel it at the bank
        deposit.setStatus(DepositStatus.EXPIRED);
        transitioned(deposit, DEPOSIT_EXPIRED);
    }

    // The bank holds the money now, so the platform owes it to the user: bank clearing -> user account
    private void succeed(Deposit deposit) {
        Account account = deposit.getAccount();
        Account clearing = systemAccountService.get(SystemAccountKind.BANK_CLEARING, deposit.getCurrency());
        JournalEntryDto entry = journalEntryService.createJournalEntry(new JournalEntryRequest(
                JournalEntryType.DEPOSIT,
                "Deposit " + deposit.getId() + " via " + deposit.getGateway(),
                AGGREGATE_TYPE,
                deposit.getId(),
                List.of(
                        new PostingRequest(clearing.getId(), PostingDirection.DEBIT, deposit.getAmount(), deposit.getCurrency()),
                        new PostingRequest(account.getId(), PostingDirection.CREDIT, deposit.getAmount(), deposit.getCurrency()))));
        // TODO: Balances only change once PostingServiceImpl applies postings (its own TODO)
        // TODO: Fees: a FEE posting to SystemAccountKind.FEES, credited amount = amount - fee

        deposit.setStatus(DepositStatus.SUCCEEDED);
        deposit.setJournalEntry(journalEntryRepository.getReferenceById(entry.id()));
        transitioned(deposit, DEPOSIT_SUCCEEDED);
        log.info("Deposit {} succeeded, entry {}", deposit.getId(), entry.id());
    }

    private void fail(Deposit deposit, String reason) {
        deposit.setStatus(DepositStatus.FAILED);
        deposit.setFailureReason(reason);
        transitioned(deposit, DEPOSIT_FAILED);
        log.info("Deposit {} failed: {}", deposit.getId(), reason);
    }

    // TODO: Once gateway_reference is set by submitToBank, also require it to equal result.reference()
    private static boolean matches(Deposit deposit, BankPaymentResult result) {
        return deposit.getAmount() == result.amount() && deposit.getCurrency().equals(result.currency());
    }

    private static DepositStatus toDepositStatus(BankPaymentStatus status) {
        return switch (status) {
            case SUCCEEDED -> DepositStatus.SUCCEEDED;
            case FAILED -> DepositStatus.FAILED;
            case PENDING -> DepositStatus.PENDING;
        };
    }

    private static Long parseDepositId(String walletDepositId) {
        try {
            return Long.valueOf(walletDepositId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Deposit lockDeposit(Long id) {
        return depositRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, DEPOSIT_NOT_FOUND));
    }

    // Saved and flushed first so the event carries the new updatedAt
    private void transitioned(Deposit deposit, String eventType) {
        DepositDto dto = depositMapper.toDto(depositRepository.saveAndFlush(deposit));
        outboxEventService.record(AGGREGATE_TYPE, deposit.getId(), eventType, dto);
    }
}
