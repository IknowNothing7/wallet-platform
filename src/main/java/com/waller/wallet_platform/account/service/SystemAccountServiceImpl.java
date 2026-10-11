package com.waller.wallet_platform.account.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.SystemAccountKind;
import com.waller.wallet_platform.account.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class SystemAccountServiceImpl implements SystemAccountService {

    private final AccountRepository accountRepository;

    // Created lazily per currency, so a new currency needs no migration
    @Override
    @Transactional
    public Account get(SystemAccountKind kind, String currency) {
        return accountRepository.findBySystemKindAndCurrency(kind, currency).orElseGet(() -> {
            if (accountRepository.insertSystemAccountIfAbsent(kind.name(), currency) == 1) {
                log.info("System account {} {} created", kind, currency);
            }
            return accountRepository.findBySystemKindAndCurrency(kind, currency)
                    .orElseThrow(() -> new IllegalStateException("System account " + kind + " " + currency + " missing"));
        });
    }

}
