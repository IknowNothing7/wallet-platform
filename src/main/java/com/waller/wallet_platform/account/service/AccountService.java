package com.waller.wallet_platform.account.service;

import java.util.List;
import com.waller.wallet_platform.account.dto.AccountDto;
import com.waller.wallet_platform.account.dto.AccountRequest;

public interface AccountService {

    AccountDto createAnAccount(AccountRequest request);

    AccountDto getAccount(Long id);

    /** Accounts owned by the logged-in user. */
    List<AccountDto> getMyAccounts();
}
