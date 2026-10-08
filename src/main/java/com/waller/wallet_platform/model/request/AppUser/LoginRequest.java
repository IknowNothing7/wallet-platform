package com.waller.wallet_platform.model.request.AppUser;

import java.io.Serializable;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
public class LoginRequest  implements  Serializable{
    
    @Email
    @NotNull
    private String email;

    @NotEmpty
    private String password;

}
