package com.waller.wallet_platform.service;

import org.springframework.stereotype.Service;

import com.waller.wallet_platform.mapper.AccountMapper;
import com.waller.wallet_platform.model.entites.Account;
import com.waller.wallet_platform.model.request.AccountRequest;
import com.waller.wallet_platform.model.response.ApiResponse;
import com.waller.wallet_platform.repositories.AccountRepository;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor 
@Getter 
@Setter 
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final AccountRequest accountRequest;
    private final AccountMapper accountMapper;

    @Override
    public ApiResponse<Account> createAnAccount(AccountRequest AccountRequest) {

        Account toEntity = accountMapper.createAnAccount(accountRequest);
        accountRepository.save(toEntity);
        log.info("NEW ACCOUNT HAS BEEN SAVED SUCCESSFULY");
        
        return ApiResponse.ok(null);
    }

}
