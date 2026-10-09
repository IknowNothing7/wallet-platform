package com.waller.wallet_platform.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.waller.wallet_platform.model.dto.TransferDto;
import com.waller.wallet_platform.model.response.ApiResponse;
import com.waller.wallet_platform.model.response.PageResponse;
import com.waller.wallet_platform.service.TransferService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/transfer")
public class TransferController {

    private final TransferService transferService;

    @GetMapping("/{id}")
    public ApiResponse<TransferDto> getTransfer(@PathVariable Long id) {
        return ApiResponse.ok(transferService.getTransfer(id));
    }

    @GetMapping("/account/{accountId}")
    public ApiResponse<PageResponse<TransferDto>> getAccountTransfers(
            @PathVariable Long accountId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(transferService.getAccountTransfers(accountId, page, size));
    }

}
