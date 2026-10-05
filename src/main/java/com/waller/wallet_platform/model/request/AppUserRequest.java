package com.waller.wallet_platform.model.request;

import java.io.Serializable;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;

@AllArgsConstructor 
public class AppUserRequest implements Serializable{

    @NotNull(message = "Name can not be empty")
    private String name;
    @NotNull(message = "Email can not be empty")
    @Email
    private String email;
    @NotNull(message = "Email can not be empty")
    private String password;

}
