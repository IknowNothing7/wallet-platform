package com.waller.wallet_platform.user.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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
import com.waller.wallet_platform.user.dto.AppUserDto;
import com.waller.wallet_platform.user.dto.AppUserRequest;
import com.waller.wallet_platform.user.service.AppUserService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/user")
public class AppUserController {

    private final AppUserService userService;

    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AppUserDto> createUser(@Valid @RequestBody AppUserRequest request) {
        return ApiResponse.ok(userService.createUser(request));
    }

    // Exposes every user's email, so admins only
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResponse<AppUserDto>> getUsers(
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(userService.getUsers(page, size));
    }

}
