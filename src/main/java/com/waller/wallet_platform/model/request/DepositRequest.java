package com.waller.wallet_platform.model.request;

import java.io.Serializable;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class DepositRequest implements Serializable {

    @NotNull(message = "account id can not be null")
    private Long accountId;

    @NotNull(message = "amount can not be null")
    @Positive(message = "amount must be positive")
    private Long amount;

    @NotNull(message = "currency can not be null")
    @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code")
    private String currency;

    @NotBlank(message = "gateway can not be empty")
    @Size(max = 50)
    private String gateway;

    @NotBlank(message = "gatewayReference can not be empty")
    @Size(max = 255)
    private String gatewayReference;

}
