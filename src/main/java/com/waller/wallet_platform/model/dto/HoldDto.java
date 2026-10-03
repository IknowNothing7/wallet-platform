package com.waller.wallet_platform.model.dto;

import java.io.Serializable;

import com.waller.wallet_platform.model.enums.HoldStatus;

import lombok.Builder;

@Builder 
public record HoldDto(
    int id,
    String createdAt,
    String updatetAt,
    int accountId,
    int amount,
    String currency,
    HoldStatus status,
    String expiresAt,
    String releasedAt

) implements Serializable{

}
