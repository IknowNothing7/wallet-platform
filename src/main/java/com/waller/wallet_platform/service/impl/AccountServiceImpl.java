package com.waller.wallet_platform.service.impl;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.mapper.AccountMapper;
import com.waller.wallet_platform.model.dto.AccountDto;
import com.waller.wallet_platform.model.entites.Account;
import com.waller.wallet_platform.model.entites.AppUser;
import com.waller.wallet_platform.model.request.AccountRequest;
import com.waller.wallet_platform.repositories.AccountRepository;
import com.waller.wallet_platform.repositories.AppUserRepository;

import com.waller.wallet_platform.service.AccountService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final AccountMapper accountMapper;

    @Override
    @Transactional
    public AccountDto createAnAccount(AccountRequest request) {
        AppUser user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "User " + request.getUserId() + " not found"));

        Account account = accountMapper.createAnAccount(request);
        account.setUser(user);
        Account saved = accountRepository.save(account);
        log.info("Account {} created for user {}", saved.getId(), user.getId());

        return accountMapper.toAccountDto(saved);
    }

}
