package com.waller.wallet_platform.model.request;

import java.io.Serializable;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder 
public class AppUserRequest implements Serializable{

    @NotNull(message = "Name can not be empty")
    private String name;
    @NotNull(message = "Email can not be empty")
    private String email;

}
