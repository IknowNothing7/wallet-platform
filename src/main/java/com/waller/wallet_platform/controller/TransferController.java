package com.waller.wallet_platform.controller;

import com.waller.wallet_platform.model.request.TransferRequest;
import com.waller.wallet_platform.service.OutboxEventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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
    private final OutboxEventService outboxEventService;


    @GetMapping("/{id}")
    public ApiResponse<TransferDto> getTransfer(@PathVariable("id") Long id) {
        return ApiResponse.ok(transferService.getTransfer(id));
    }

    @GetMapping("/account/{accountId}")
    public ApiResponse<PageResponse<TransferDto>> getAccountTransfers(
            @PathVariable("accountId") Long accountId,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(transferService.getAccountTransfers(accountId, page, size));
    }

    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TransferDto> createTransfer( @Valid @RequestBody TransferRequest request) {
        return ApiResponse.ok(transferService.createTransfer(request));
    }

}
