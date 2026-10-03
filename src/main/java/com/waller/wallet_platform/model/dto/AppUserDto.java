package com.waller.wallet_platform.model.dto;

import java.io.Serializable;

import com.waller.wallet_platform.model.enums.UserRole;

import lombok.Builder;

@Builder
public record AppUserDto(
        int id,
        String createdAt,
        String updatetAt,
        String name,
        String email,
        UserRole role) implements Serializable {
}