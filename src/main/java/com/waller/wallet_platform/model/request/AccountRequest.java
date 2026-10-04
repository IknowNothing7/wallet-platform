package com.waller.wallet_platform.model.request;

import java.io.Serializable;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder 
public class AccountRequest implements Serializable{


    private String currency;
    private int balance;
    @NotNull(message = "UserId can not be empty")
    private int userId;

}
