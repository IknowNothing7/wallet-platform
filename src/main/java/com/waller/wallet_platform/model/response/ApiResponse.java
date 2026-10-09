package com.waller.wallet_platform.model.response;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class ApiResponse<P extends Serializable> implements Serializable {

    private String message;
    private P payload;
    private boolean success;

    public static <P extends Serializable> ApiResponse<P> ok(P payload) {
        return new ApiResponse<>("", payload, true);
    }

    public static <P extends Serializable> ApiResponse<P> error(String message) {
        return error(message, null);
    }

    public static <P extends Serializable> ApiResponse<P> error(String message, P payload) {
        return new ApiResponse<>(message, payload, false);
    }

}
