package com.waller.wallet_platform.controller;

import java.util.ArrayList;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.waller.wallet_platform.model.dto.AccountDto;
import com.waller.wallet_platform.model.request.AccountRequest;
import com.waller.wallet_platform.model.response.ApiResponse;
import com.waller.wallet_platform.service.AccountService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/account")
public class AccountController {

    private final AccountService accountService;

    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AccountDto> createNewAccount(@Valid @RequestBody AccountRequest accountRequest) {
        return ApiResponse.ok(accountService.createAnAccount(accountRequest));
    }

    // The logged-in user's accounts
    @GetMapping
    public ApiResponse<ArrayList<AccountDto>> getMyAccounts() {
        return ApiResponse.ok(new ArrayList<>(accountService.getMyAccounts()));
    }

    @GetMapping("/{id}")
    public ApiResponse<AccountDto> getAccount(@PathVariable Long id) {
        return ApiResponse.ok(accountService.getAccount(id));
    }

}
