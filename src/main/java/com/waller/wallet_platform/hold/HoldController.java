package com.waller.wallet_platform.hold;

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

@RestController
@RequiredArgsConstructor
@RequestMapping("/hold")
public class HoldController {

    private final HoldService holdService;

    // A merchant reserves funds on a customer's account, e.g. at checkout
    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MERCHANT')")
    public ApiResponse<HoldDto> createHold(@Valid @RequestBody HoldRequest request) {
        return ApiResponse.ok(holdService.createHold(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<HoldDto> getHold(@PathVariable("id") Long id) {
        return ApiResponse.ok(holdService.getHold(id));
    }

    @GetMapping("/account/{accountId}")
    public ApiResponse<PageResponse<HoldDto>> getAccountHolds(
            @PathVariable("accountId") Long accountId,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(holdService.getAccountHolds(accountId, page, size));
    }

}
