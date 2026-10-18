package com.waller.wallet_platform.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.waller.wallet_platform.user.entity.UserRole;

// USER or MERCHANT; ADMIN is never granted over HTTP (see AdminBootstrap)
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class UserRoleRequest {

    @NotNull(message = "Role can not be empty")
    private UserRole role;

}
