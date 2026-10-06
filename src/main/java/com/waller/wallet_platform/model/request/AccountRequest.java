package com.waller.wallet_platform.model.request;

import java.io.Serializable;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class AccountRequest implements Serializable {

    @NotNull(message = "Currency can not be empty")
    @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a 3-letter ISO code")
    private String currency;

    @NotNull(message = "UserId can not be empty")
    private Long userId;

}
