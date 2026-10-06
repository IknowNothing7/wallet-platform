package com.waller.wallet_platform.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.waller.wallet_platform.model.dto.AppUserDto;
import com.waller.wallet_platform.model.request.AppUserRequest;
import com.waller.wallet_platform.model.response.ApiResponse;
import com.waller.wallet_platform.service.AppUserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

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

}
