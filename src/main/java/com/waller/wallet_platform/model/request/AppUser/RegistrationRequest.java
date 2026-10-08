package com.waller.wallet_platform.model.request.AppUser;

import java.io.Serializable;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class RegistrationRequest  implements Serializable{

    @NotBlank 
    @Size(max = 100)
    private String name;

    @NotNull
    @Email 
    @Size(max = 100)
    private String email;

    @NotBlank
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    private String password;

    @NotBlank
    private String confirmPassword;

}
