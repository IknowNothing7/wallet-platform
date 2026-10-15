package com.waller.wallet_platform.user.dto;

import java.io.Serializable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
public class AppUserRequest implements Serializable {

    @NotBlank(message = "Name can not be empty")
    @Size(max = 100)
    private String name;

    @NotBlank(message = "Email can not be empty")
    @Email
    @Size(max = 100)
    private String email;

    @NotBlank(message = "Password can not be empty")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    private String password;

}
