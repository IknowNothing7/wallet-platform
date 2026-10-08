package com.waller.wallet_platform.model.response;

import java.io.Serializable;

import com.waller.wallet_platform.model.dto.AppUserDto;

import lombok.Builder;

@Builder
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        AppUserDto user) implements Serializable {
}
