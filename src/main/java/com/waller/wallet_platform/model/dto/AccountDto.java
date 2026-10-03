package com.waller.wallet_platform.model.dto;

import java.io.Serializable;

import com.waller.wallet_platform.model.enums.AccountStatus;
import com.waller.wallet_platform.model.enums.AccountType;

import lombok.Builder;

@Builder
public record AccountDto(
        int id,
        int createdAt,
        int updatetAt,
        int balance,
        String currency,
        AccountType type,
        AccountStatus status,
        int userId

) implements Serializable {

}
