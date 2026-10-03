package com.waller.wallet_platform.model.dto;

import java.io.Serializable;

import com.waller.wallet_platform.model.enums.TransferStatus;

import lombok.Builder;

@Builder
public record TransferDto(
        int id,
        String createdAt,
        String updatetAt,
        int fromAcountId,
        int toAccountId,
        int amount,
        String currency,
        TransferStatus status,
        String failureReason

) implements Serializable {

}
