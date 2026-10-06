package com.waller.wallet_platform.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.waller.wallet_platform.model.dto.DepositDto;
import com.waller.wallet_platform.model.request.DepositRequest;
import com.waller.wallet_platform.model.response.ApiResponse;
import com.waller.wallet_platform.service.DepositService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/deposit")
public class DepositController {

    private final DepositService depositService;

    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DepositDto> createDeposit(@Valid @RequestBody DepositRequest request) {
        return ApiResponse.ok(depositService.createDeposit(request));
    }

}
