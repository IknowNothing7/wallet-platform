package com.waller.wallet_platform.account;

import java.util.List;

public interface AccountService {

    AccountDto createAnAccount(AccountRequest request);

    AccountDto getAccount(Long id);

    /** Accounts owned by the logged-in user. */
    List<AccountDto> getMyAccounts();
}
