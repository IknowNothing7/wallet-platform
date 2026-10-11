package com.waller.wallet_platform.deposit.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.waller.wallet_platform.common.web.ApiResponse;
import com.waller.wallet_platform.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import com.waller.wallet_platform.deposit.dto.DepositDto;
import com.waller.wallet_platform.deposit.dto.DepositRequest;
import com.waller.wallet_platform.deposit.service.DepositService;

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

    @GetMapping("/{id}")
    public ApiResponse<DepositDto> getDeposit(@PathVariable("id") Long id) {
        return ApiResponse.ok(depositService.getDeposit(id));
    }

    @GetMapping("/account/{accountId}")
    public ApiResponse<PageResponse<DepositDto>> getAccountDeposits(
            @PathVariable("accountId") Long accountId,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(depositService.getAccountDeposits(accountId, page, size));
    }

}
