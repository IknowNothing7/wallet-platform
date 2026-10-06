package com.waller.wallet_platform.model.request;

import java.io.Serializable;

import com.waller.wallet_platform.model.enums.DepositStatus;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@AllArgsConstructor 
@Getter 
@Setter  
public class DepositRequest implements Serializable{

    @NotNull(message = "account id can not be null")
    private int accountId;

    @NotNull(message = "amount can not be null")
    private int amount;

    @NotNull(message = "currency can not be null")
    private char currency;

    @NotNull(message = "gatewayString can not be null")
    private String gatewayString;

    @NotNull(message = "gatewayReference can not be null")
    private String gatewayReference;

    private DepositStatus status = DepositStatus.PENDING;

}
