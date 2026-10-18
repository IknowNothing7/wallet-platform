package com.waller.wallet_platform.transfer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import java.io.Serializable;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class ReverseRequest implements Serializable {

    // Ends up in the reversal journal entry description (VARCHAR(255)) after "Reversal of transfer <id>: "
    @NotBlank(message = "reason can not be blank")
    @Size(max = 200, message = "reason must be at most 200 characters")
    private String reason;

    @Override
    public String toString() {
        return "ReverseRequest{" +
                "reason='" + reason + '\'' +
                '}';
    }
}
