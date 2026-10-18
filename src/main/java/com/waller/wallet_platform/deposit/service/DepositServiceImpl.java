package com.waller.wallet_platform.deposit.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.AccountStatus;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.security.AccountAccessChecker;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.waller.wallet_platform.deposit.dto.DepositDto;
import com.waller.wallet_platform.deposit.dto.DepositRequest;
import com.waller.wallet_platform.deposit.entity.Deposit;
import com.waller.wallet_platform.deposit.mapper.DepositMapper;
import com.waller.wallet_platform.deposit.repository.DepositRepository;

@Service
@Slf4j
@RequiredArgsConstructor
public class DepositServiceImpl implements DepositService {

    private final DepositRepository depositRepository;
    private final AccountRepository accountRepository;
    private static final String DEPOSIT_NOT_FOUND = "Deposit not found";
    private static final String ACCOUNT_NOT_FOUND = "Account not found";
    private static final String AGGREGATE_TYPE = "deposit";
    private static final String DEPOSIT_CREATED = "DepositCreated";

    private final DepositMapper depositMapper;
    private final AccountAccessChecker accountAccessChecker;
    private final OutboxEventService outboxEventService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${deposit.pending-ttl:PT30M}")
    private Duration pendingTtl;

    @Override
    @Transactional
    public DepositDto createDeposit(DepositRequest request) {
        Account account = accountRepository.findUserAccountById(request.getAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Account " + request.getAccountId() + " not found"));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acceptor's account is not active");
        }

        if (!account.getCurrency().equals(request.getCurrency())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Deposit currency does not match account currency " + account.getCurrency());
        }

        Deposit deposit = depositMapper.requestToEntity(request);
        deposit.setAccount(account);
        deposit.setExpiresAt(Instant.now().plus(pendingTtl));
        Deposit saved = depositRepository.save(deposit);
        log.info("Deposit {} created for account {}", saved.getId(), account.getId());

        DepositDto dto = depositMapper.toDto(saved);
        outboxEventService.record(AGGREGATE_TYPE, saved.getId(), DEPOSIT_CREATED, dto);
        // Submitted to the bank by DepositSubmissionListener after this transaction commits
        eventPublisher.publishEvent(new DepositCreatedEvent(saved.getId()));
        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public DepositDto getDeposit(Long id) {
        Deposit deposit = depositRepository.findWithAccountById(id)
                .filter(d -> accountAccessChecker.canAccess(d.getAccount()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, DEPOSIT_NOT_FOUND));
        return depositMapper.toDto(deposit);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DepositDto> getAccountDeposits(Long accountId, int page, int size) {
        if (!accountAccessChecker.canAccess(accountId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ACCOUNT_NOT_FOUND);
        }
        return PageResponse.of(
                depositRepository.findByAccountIdOrderByCreatedAtDescIdDesc(accountId, PageRequest.of(page, size)),
                depositMapper::toDto);
    }
}
