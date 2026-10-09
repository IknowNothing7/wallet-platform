package com.waller.wallet_platform.service.impl;

import java.util.List;

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
import com.waller.wallet_platform.security.AccountAccessChecker;
import com.waller.wallet_platform.utils.ApiUtils;

import com.waller.wallet_platform.service.AccountService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private static final String ACCOUNT_NOT_FOUND = "Account not found";

    private final AccountMapper accountMapper;
    private final AccountAccessChecker accountAccessChecker;

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

    @Override
    @Transactional(readOnly = true)
    public AccountDto getAccount(Long id) {
        // Someone else's account looks the same as a missing one, so ids can't be probed
        if (!accountAccessChecker.canAccess(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ACCOUNT_NOT_FOUND);
        }
        return accountRepository.findById(id)
                .map(accountMapper::toAccountDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ACCOUNT_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountDto> getMyAccounts() {
        return accountRepository.findByUserEmailIgnoreCaseOrderByIdAsc(ApiUtils.getCurrentUsername()).stream()
                .map(accountMapper::toAccountDto)
                .toList();
    }

}
