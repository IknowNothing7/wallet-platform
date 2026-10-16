package com.waller.wallet_platform.bank.client;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import lombok.extern.slf4j.Slf4j;
import com.waller.wallet_platform.bank.dto.BankPayment;
import com.waller.wallet_platform.bank.dto.BankPaymentRequest;

/**
 * HTTP client for bank-sim. Never call it inside a transaction: the call can take up to the request timeout
 * (longer with bank-sim's latency scenario) and would hold a database connection and row locks meanwhile.
 */
@Component
@Slf4j
public class BankClient {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final RestClient restClient;

    public BankClient(
            @Value("${bank.base-url:http://localhost:8081}") String baseUrl,
            @Value("${bank.request-timeout:PT5S}") Duration requestTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(requestTimeout);
        requestFactory.setReadTimeout(requestTimeout);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    /**
     * Asks the bank to collect the money; the result arrives later through the webhook.
     * Safe to repeat with the same key: bank-sim returns the original payment and sends no second result.
     */
    public BankPayment createPayment(String idempotencyKey, BankPaymentRequest request) {
        // TODO: Retry 503s and timeouts here with the same key (bank-sim's failureRate / lostResponseRate
        //  scenarios), or leave it to the reconciliation job. Decide which, and don't do both.
        // TODO: Map errors: 4xx means the request is wrong and retrying won't help (mark the deposit FAILED),
        //  5xx or timeout means the outcome is unknown (keep it PENDING).
        return restClient.post()
                .uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
                .body(request)
                .retrieve()
                .body(BankPayment.class);
    }

    /** Used by reconciliation when a webhook never arrived (bank-sim's DROP scenario). */
    public BankPayment getPayment(String reference) {
        // TODO: 404 means the bank never stored the payment (e.g. failed before storing); handle it in the caller
        return restClient.get()
                .uri("/payments/{reference}", reference)
                .retrieve()
                .body(BankPayment.class);
    }
}
