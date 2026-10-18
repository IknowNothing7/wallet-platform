package com.waller.wallet_platform.account.service;

import java.util.List;

import com.waller.wallet_platform.outbox.service.OutboxEventService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import com.waller.wallet_platform.security.AccountAccessChecker;
import com.waller.wallet_platform.common.util.ApiUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.waller.wallet_platform.account.dto.AccountDto;
import com.waller.wallet_platform.account.dto.AccountRequest;
import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.mapper.AccountMapper;
import com.waller.wallet_platform.account.repository.AccountRepository;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private static final String ACCOUNT_NOT_FOUND = "Account not found";
    private static final String AGGREGATE_TYPE = "account";
    private static final String ACCOUNT_CREATED = "AccountCreated";

    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final AccountMapper accountMapper;
    private final AccountAccessChecker accountAccessChecker;
    private final OutboxEventService outboxEventService;


    @Override
    @Transactional
    public AccountDto createAnAccount(AccountRequest request) {
        // The owner is always the logged-in user, never one named in the request
        AppUser user = userRepository.findByEmailIgnoreCase(ApiUtils.getCurrentUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        Account account = accountMapper.createAnAccount(request);
        account.setUser(user);
        Account saved = accountRepository.save(account);
        log.info("Account {} created for user {}", saved.getId(), user.getId());

        outboxEventService.record(AGGREGATE_TYPE,account.getId(),ACCOUNT_CREATED,accountMapper.toAccountDto(saved));
        return accountMapper.toAccountDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountDto getAccount(Long id) {
        // Someone else's account gets the same 404 as a missing one
        Account account = accountRepository.findById(id)
                .filter(accountAccessChecker::canAccess)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ACCOUNT_NOT_FOUND));
        return accountMapper.toAccountDto(account);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountDto> getMyAccounts() {
        // The principal's name is the user's email (see AppUserDetailsService)
        return accountRepository.findByUserEmailIgnoreCaseOrderByIdAsc(ApiUtils.getCurrentUsername()).stream()
                .map(accountMapper::toAccountDto)
                .toList();
    }

}
