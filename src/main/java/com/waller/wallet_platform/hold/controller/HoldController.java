package com.waller.wallet_platform.hold.controller;

import com.waller.wallet_platform.common.util.ApiUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.waller.wallet_platform.hold.service.HoldService;
import com.waller.wallet_platform.hold.dto.HoldCaptureRequest;
import com.waller.wallet_platform.hold.dto.HoldDto;
import com.waller.wallet_platform.hold.dto.HoldRequest;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
@Slf4j
public class HoldController {

    private final HoldService holdService;

    // A merchant reserves funds on a customer's account, e.g. at checkout
    @PostMapping("/holds")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MERCHANT')")
    public ApiResponse<HoldDto> createHold(@Valid @RequestBody HoldRequest request) {
        log.info("Endpoint {}", ApiUtils.getMethodName());
        return ApiResponse.ok(holdService.createHold(request));
    }

    @GetMapping("/holds/{id}")
    public ApiResponse<HoldDto> getHold(@PathVariable("id") Long id) {
        return ApiResponse.ok(holdService.getHold(id));
    }

    @GetMapping("/accounts/{accountId}/holds")
    public ApiResponse<PageResponse<HoldDto>> getAccountHolds(
            @PathVariable("accountId") Long accountId,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(holdService.getAccountHolds(accountId, page, size));
    }

    // Safe to retry: repeating a transition on a hold already in that state returns it unchanged
    @PostMapping("/holds/{id}/capture")
    @PreAuthorize("hasRole('MERCHANT')")
    public ApiResponse<HoldDto> captureHold(@PathVariable("id") Long id,
                                            @Valid @RequestBody HoldCaptureRequest request) {
        return ApiResponse.ok(holdService.captureHold(id, request));
    }

    @PostMapping("/holds/{id}/release")
    @PreAuthorize("hasRole('MERCHANT')")
    public ApiResponse<HoldDto> releaseHold(@PathVariable("id") Long id) {
        return ApiResponse.ok(holdService.releaseHold(id));
    }

    @PostMapping("/holds/{id}/expire")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<HoldDto> expireHold(@PathVariable("id") Long id) {
        return ApiResponse.ok(holdService.expireHold(id));
    }

}
