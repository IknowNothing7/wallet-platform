package com.waller.wallet_platform.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.waller.wallet_platform.model.request.AccountRequest;
import com.waller.wallet_platform.service.AccountServiceImpl;

import io.swagger.v3.oas.annotations.parameters.RequestBody;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController 
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/account")
public class AccountController {

    private final AccountServiceImpl accountService;

    @PostMapping("/create")
    public void createNewAccount(@RequestBody AccountRequest accountRequest){
    accountService.createAnAccount(accountRequest);
    log.info("ACCOUNT HAS BEEN CREATED");
    }

}
