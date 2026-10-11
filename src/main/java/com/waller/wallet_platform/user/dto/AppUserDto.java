package com.waller.wallet_platform.user.dto;

import java.io.Serializable;
import java.time.Instant;
import lombok.Builder;
import com.waller.wallet_platform.user.entity.UserRole;

@Builder
public record AppUserDto(
        Long id,
        Instant createdAt,
        Instant updatedAt,
        String name,
        String email,
        UserRole role) implements Serializable {
}
