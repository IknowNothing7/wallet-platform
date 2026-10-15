package com.waller.wallet_platform.account.controller;

import java.util.ArrayList;

import com.waller.wallet_platform.common.util.ApiUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.waller.wallet_platform.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.waller.wallet_platform.account.dto.AccountDto;
import com.waller.wallet_platform.account.dto.AccountRequest;
import com.waller.wallet_platform.account.service.AccountService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/account")
@Slf4j
public class AccountController {

    private final AccountService accountService;

    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AccountDto> createNewAccount(@Valid @RequestBody AccountRequest accountRequest) {
        log.info("Endpoint {}", ApiUtils.getMethodName());
        return ApiResponse.ok(accountService.createAnAccount(accountRequest));
    }

      // The logged-in user's accounts
    @GetMapping
    public ApiResponse<ArrayList<AccountDto>> getMyAccounts() {
        return ApiResponse.ok(new ArrayList<>(accountService.getMyAccounts()));
    }

    @GetMapping("/{id}")
    public ApiResponse<AccountDto> getAccount(@PathVariable("id") Long id) {
        return ApiResponse.ok(accountService.getAccount(id));
    }

}
