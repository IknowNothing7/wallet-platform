package com.waller.wallet_platform.service;

import com.waller.wallet_platform.model.dto.AccountDto;
import com.waller.wallet_platform.model.request.AccountRequest;

public interface AccountService {

    AccountDto createAnAccount(AccountRequest request);
}
