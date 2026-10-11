package com.waller.wallet_platform.account;

import java.io.Serializable;
import java.time.Instant;
import lombok.Builder;

@Builder
public record AccountDto(
        Long id,
        Instant createdAt,
        Instant updatedAt,
        Long balance,
        String currency,
        AccountType type,
        AccountStatus status,
        Long userId

) implements Serializable {

}
