package com.waller.wallet_platform.service;

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
import com.waller.wallet_platform.repositories.DepositRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class DepositServiceImpl implements DepositService {

    private final DepositRepository depositRepository;
    private final AccountRepository accountRepository;
    private final DepositMapper depositMapper;

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
}
