package com.waller.wallet_platform.service.impl;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.mapper.DepositMapper;
import com.waller.wallet_platform.model.dto.DepositDto;
import com.waller.wallet_platform.model.entites.Account;
import com.waller.wallet_platform.model.entites.Deposit;
import com.waller.wallet_platform.model.request.DepositRequest;
import com.waller.wallet_platform.repositories.AccountRepository;
import com.waller.wallet_platform.model.response.PageResponse;
import com.waller.wallet_platform.repositories.DepositRepository;
import com.waller.wallet_platform.security.AccountAccessChecker;

import com.waller.wallet_platform.service.DepositService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class DepositServiceImpl implements DepositService {

    private final DepositRepository depositRepository;
    private final AccountRepository accountRepository;
    private static final String DEPOSIT_NOT_FOUND = "Deposit not found";
    private static final String ACCOUNT_NOT_FOUND = "Account not found";

    private final DepositMapper depositMapper;
    private final AccountAccessChecker accountAccessChecker;

    @Override
    @Transactional
    public DepositDto createDeposit(DepositRequest request) {
        Account account = accountRepository.findById(request.getAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Account " + request.getAccountId() + " not found"));

        if (!account.getCurrency().equals(request.getCurrency())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Deposit currency does not match account currency " + account.getCurrency());
        }

        Deposit deposit = depositMapper.requestToEntity(request);
        deposit.setAccount(account);
        Deposit saved = depositRepository.save(deposit);
        log.info("Deposit {} created for account {}", saved.getId(), account.getId());

        return depositMapper.toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public DepositDto getDeposit(Long id) {
        // Someone else's deposit looks the same as a missing one, so ids can't be probed
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
