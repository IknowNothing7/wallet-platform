package com.waller.wallet_platform.model.dto;

import java.io.Serializable;
import java.time.Instant;

import com.waller.wallet_platform.model.enums.AccountStatus;
import com.waller.wallet_platform.model.enums.AccountType;

import lombok.Builder;

@Builder
public record AccountDto(
        int id,
        Instant createdAt,
        Instant updatedAt,
        int balance,
        String currency,
        AccountType type,
        AccountStatus status,
        int userId

) implements Serializable {

}
