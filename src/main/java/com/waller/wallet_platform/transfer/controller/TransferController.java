package com.waller.wallet_platform.transfer.controller;

import com.waller.wallet_platform.common.util.ApiUtils;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.waller.wallet_platform.common.web.ApiResponse;
import com.waller.wallet_platform.common.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import com.waller.wallet_platform.transfer.dto.TransferDto;
import com.waller.wallet_platform.transfer.dto.TransferRequest;
import com.waller.wallet_platform.transfer.service.TransferService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/transfer")
@Slf4j
public class TransferController {

    private final TransferService transferService;


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
        log.info("Endpoint {}", ApiUtils.getMethodName());
        return ApiResponse.ok(transferService.createTransfer(request));
    }

}
