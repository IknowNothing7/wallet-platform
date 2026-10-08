package com.waller.wallet_platform.model.request.AppUser;

import java.io.Serializable;

import jakarta.validation.constraints.NotBlank;
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
public class UpdateUserRequest implements Serializable{
    @NotBlank(message="Username can not be empty")
    private String username;
    @NotBlank(message="Password can not be empty")
    private String password;
}
