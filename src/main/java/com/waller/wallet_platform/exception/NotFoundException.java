package com.waller.wallet_platform.exception;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class NotFoundException extends RuntimeException{

    public NotFoundException(String message) {
        super(message);
    }

}
