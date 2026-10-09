package com.waller.wallet_platform.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.waller.wallet_platform.model.dto.HoldDto;
import com.waller.wallet_platform.model.response.ApiResponse;
import com.waller.wallet_platform.model.response.PageResponse;
import com.waller.wallet_platform.service.HoldService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/hold")
public class HoldController {

    private final HoldService holdService;

    @GetMapping("/{id}")
    public ApiResponse<HoldDto> getHold(@PathVariable Long id) {
        return ApiResponse.ok(holdService.getHold(id));
    }

    @GetMapping("/account/{accountId}")
    public ApiResponse<PageResponse<HoldDto>> getAccountHolds(
            @PathVariable Long accountId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(holdService.getAccountHolds(accountId, page, size));
    }

}
