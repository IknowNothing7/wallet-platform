package com.waller.wallet_platform.model.dto;

import java.io.Serializable;
import java.time.Instant;

import com.waller.wallet_platform.model.enums.UserRole;

import lombok.Builder;

@Builder
public record AppUserDto(
        Long id,
        Instant createdAt,
        Instant updatedAt,
        String name,
        String email,
        UserRole role) implements Serializable {
}
