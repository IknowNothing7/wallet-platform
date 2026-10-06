package com.waller.wallet_platform.service;

import org.springframework.stereotype.Service;

import com.waller.wallet_platform.model.entites.Account;
import com.waller.wallet_platform.model.request.AccountRequest;
import com.waller.wallet_platform.model.response.ApiResponse;

@Service 
public interface AccountService {

    ApiResponse<Account> createAnAccount(AccountRequest AccountRequest);
}
