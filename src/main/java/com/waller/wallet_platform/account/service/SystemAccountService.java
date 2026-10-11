package com.waller.wallet_platform.account.service;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.SystemAccountKind;

// Resolves the platform's own accounts for service code that posts to them; never exposed through the API
public interface SystemAccountService {

    /** The account of this kind in this currency, created on first use. */
    Account get(SystemAccountKind kind, String currency);
}
