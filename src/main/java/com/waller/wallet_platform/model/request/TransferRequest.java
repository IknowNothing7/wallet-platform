package com.waller.wallet_platform.model.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.io.Serializable;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class TransferRequest implements Serializable {

    @NotNull(message = "accountFrom id can not be null")
    private Long idFromAccount;
    @NotNull(message = "accountTo id can not be null")
    private Long idToAccount;
    @NotNull(message = "amount can not be null")
    @Positive(message = "amount must be positive")
    private Long amount;
    @NotNull(message = "currency can not be null")
    @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code")
    private String currency;

    @Override
    public String toString() {
        return "TransferRequest{" +
                "idFromAccount=" + idFromAccount +
                ", idToAccount=" + idToAccount +
                ", amount=" + amount +
                ", currency='" + currency + '\'' +
                '}';
    }
}
