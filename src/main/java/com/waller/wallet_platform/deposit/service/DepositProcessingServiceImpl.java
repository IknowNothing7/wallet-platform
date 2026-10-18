package com.waller.wallet_platform.deposit.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
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
    // 2%, rounded down in the user's favour
    private static final long FEE_BASIS_POINTS = 200;

    private final DepositRepository depositRepository;
    private final DepositMapper depositMapper;
    private final BankClient bankClient;
    private final SystemAccountService systemAccountService;
    private final JournalEntryService journalEntryService;
    private final JournalEntryRepository journalEntryRepository;
    private final OutboxEventService outboxEventService;
    private final TransactionTemplate transactionTemplate;
    private final DepositAlerts depositAlerts;

    // Deliberately not @Transactional: the bank call happens between two short transactions
    @Override
    public DepositDto submitToBank(Long depositId) {
        Deposit deposit = depositRepository.findById(depositId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, DEPOSIT_NOT_FOUND));
        if (deposit.getStatus() != DepositStatus.PENDING) {
            return depositMapper.toDto(deposit);
        }
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
            // 2xx anyway: a retry would hit the same missing row. This means money with no deposit.
            depositAlerts.raise(DepositAlerts.Reason.UNKNOWN_DEPOSIT,
                    "Bank result {} for unknown deposit {}", result.reference(), depositId);
            return;
        }

        if (!matches(deposit, result)) {
            // Left untouched for manual review. Never credit an amount the user didn't ask for.
            depositAlerts.raise(DepositAlerts.Reason.RESULT_MISMATCH,
                    "Bank result {} does not match deposit {} (amount/currency/reference)",
                    result.reference(), deposit.getId());
            return;
        }

        if (deposit.getStatus() != DepositStatus.PENDING) {
            if (deposit.getStatus() != toDepositStatus(result.status())) {
                // e.g. EXPIRED locally but the bank says SUCCEEDED: the money did arrive.
                // Status and ledger are left as they are; someone has to credit or refund by hand.
                depositAlerts.raise(DepositAlerts.Reason.STATUS_CONFLICT,
                        "Deposit {} is {} but the bank now reports {}",
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

    // Deliberately not @Transactional: the bank is asked first, outside any transaction
    @Override
    public void expire(Long depositId) {
        Deposit deposit = depositRepository.findById(depositId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, DEPOSIT_NOT_FOUND));
        if (!isExpirable(deposit)) {
            return;
        }

        // Ask the bank first so a payment that did settle isn't expired
        BankPayment payment = findBankPayment(deposit);
        if (payment != null && payment.status() != BankPaymentStatus.PENDING) {
            // Self-invocation skips the proxy, so applyResult needs its own transaction here
            transactionTemplate.executeWithoutResult(status -> applyResult(toResult(payment)));
            return;
        }

        // TODO: Cancel the payment at the bank once bank-sim supports it; a late success now raises STATUS_CONFLICT
        transactionTemplate.executeWithoutResult(status -> {
            Deposit locked = lockDeposit(depositId);
            // Re-checked under the lock: a webhook may have settled it while we were asking the bank
            if (!isExpirable(locked)) {
                return;
            }
            locked.setStatus(DepositStatus.EXPIRED);
            transitioned(locked, DEPOSIT_EXPIRED);
        });
    }

    // The bank holds the money now, so the platform owes it to the user minus the fee:
    // bank clearing -> user account + fees
    private void succeed(Deposit deposit) {
        Account account = deposit.getAccount();
        Account clearing = systemAccountService.get(SystemAccountKind.BANK_CLEARING, deposit.getCurrency());
        long fee = deposit.getAmount() * FEE_BASIS_POINTS / 10_000;

        List<PostingRequest> postings = new ArrayList<>();
        postings.add(new PostingRequest(clearing.getId(), PostingDirection.DEBIT, deposit.getAmount(), deposit.getCurrency()));
        postings.add(new PostingRequest(account.getId(), PostingDirection.CREDIT, deposit.getAmount() - fee, deposit.getCurrency()));
        // Postings must be positive, so small deposits whose fee rounds to 0 get no fee posting
        if (fee > 0) {
            Account fees = systemAccountService.get(SystemAccountKind.FEES, deposit.getCurrency());
            postings.add(new PostingRequest(fees.getId(), PostingDirection.CREDIT, fee, deposit.getCurrency()));
        }
        JournalEntryDto entry = journalEntryService.createJournalEntry(new JournalEntryRequest(
                JournalEntryType.DEPOSIT,
                "Deposit " + deposit.getId() + " via " + deposit.getGateway(),
                AGGREGATE_TYPE,
                deposit.getId(),
                postings));

        deposit.setStatus(DepositStatus.SUCCEEDED);
        deposit.setJournalEntry(journalEntryRepository.getReferenceById(entry.id()));
        transitioned(deposit, DEPOSIT_SUCCEEDED);
        log.info("Deposit {} succeeded, fee {}, entry {}", deposit.getId(), fee, entry.id());
    }

    private void fail(Deposit deposit, String reason) {
        deposit.setStatus(DepositStatus.FAILED);
        deposit.setFailureReason(reason);
        transitioned(deposit, DEPOSIT_FAILED);
        log.info("Deposit {} failed: {}", deposit.getId(), reason);
    }

    // A null reference is allowed: the webhook can arrive before submitToBank has stored it
    private static boolean matches(Deposit deposit, BankPaymentResult result) {
        return deposit.getAmount() == result.amount()
                && deposit.getCurrency().equals(result.currency())
                && (deposit.getGatewayReference() == null || deposit.getGatewayReference().equals(result.reference()));
    }

    private static boolean isExpirable(Deposit deposit) {
        return deposit.getStatus() == DepositStatus.PENDING
                && deposit.getExpiresAt() != null
                && !deposit.getExpiresAt().isAfter(Instant.now());
    }

    // Null when the bank has no payment for this deposit: never submitted, or it failed before storing it
    private BankPayment findBankPayment(Deposit deposit) {
        if (deposit.getGatewayReference() == null) {
            return null;
        }
        try {
            return bankClient.getPayment(deposit.getGatewayReference());
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    private static BankPaymentResult toResult(BankPayment payment) {
        return new BankPaymentResult(
                payment.reference(),
                payment.walletDepositId(),
                payment.status(),
                payment.amount(),
                payment.currency(),
                payment.failureReason(),
                Instant.now());
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
