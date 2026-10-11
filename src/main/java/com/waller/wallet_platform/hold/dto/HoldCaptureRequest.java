package com.waller.wallet_platform.hold.dto;

import java.io.Serializable;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class HoldCaptureRequest implements Serializable {

    // Where the captured funds go, e.g. the merchant's account
    @NotNull(message = "destination account id can not be null")
    private Long destinationAccountId;

}
