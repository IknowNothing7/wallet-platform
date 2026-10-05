package com.waller.wallet_platform.model.response;

import java.io.Serializable;

import org.apache.commons.lang3.StringUtils;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Builder 
@Getter 
@Setter 
public class ApiResponse<P extends Serializable> implements Serializable {

    private String message;
    private P payload;
    private boolean success;

        public static <P extends Serializable> ApiResponse<P> ok(P payload) {
        return new ApiResponse<>(StringUtils.EMPTY, payload, true);
    }

}
