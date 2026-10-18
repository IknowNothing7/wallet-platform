package com.waller.wallet_platform.bank.client;

import org.springframework.http.HttpStatusCode;

/**
 * The bank answered 4xx: it understood the request and refused it, so resending the same request can't succeed.
 * 5xx and timeouts are not mapped to this; for those the outcome is unknown and the caller should keep waiting.
 */
public class BankRejectedException extends RuntimeException {

    private final HttpStatusCode status;
    private final String responseBody;

    public BankRejectedException(HttpStatusCode status, String responseBody) {
        super("Bank rejected the request with " + status + ": " + responseBody);
        this.status = status;
        this.responseBody = responseBody;
    }

    public HttpStatusCode getStatus() {
        return status;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
