package com.waller.wallet_platform.model.request;

import java.io.Serializable;

import com.waller.wallet_platform.model.enums.PostingDirection;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class PostingRequest implements Serializable {

    @NotNull(message = "account id can not be null")
    private Long accountId;

    @NotNull(message = "direction can not be null")
    private PostingDirection direction;

    @NotNull(message = "amount can not be null")
    @Positive(message = "amount must be positive")
    private Long amount;

    @NotNull(message = "currency can not be null")
    @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code")
    private String currency;

}
