package com.waller.wallet_platform.bank.client;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
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
 *
 * <p>A timeout and no retries, on purpose: DepositReconciliationJob retries, reusing the deposit's idempotency key,
 * so a repeat can never pay twice. See docs/decisions.md, "Talking to the bank".
 */
@Component
@Slf4j
public class BankClient {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final RestClient restClient;

    public BankClient(
            @Value("${bank.base-url:http://localhost:8081}") String baseUrl,
            @Value("${bank.request-timeout:PT5S}") Duration requestTimeout,
            ObservationRegistry observationRegistry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(requestTimeout);
        requestFactory.setReadTimeout(requestTimeout);
        // The registry makes each call a client span and sends traceparent, so bank-sim's spans join the trace
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .build();
    }

    public BankPayment createPayment(String idempotencyKey, BankPaymentRequest request) {
        return restClient.post()
                .uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    throw new BankRejectedException(res.getStatusCode(),
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
                })
                .body(BankPayment.class);
    }

    public BankPayment getPayment(String reference) {
        return restClient.get()
                .uri("/payments/{reference}", reference)
                .retrieve()
                .body(BankPayment.class);
    }

    /** Returns the payment as it is after the cancel: CANCELLED, or its result if it had already settled. */
    public BankPayment cancelPayment(String reference) {
        return restClient.post()
                .uri("/payments/{reference}/cancel", reference)
                .retrieve()
                .body(BankPayment.class);
    }
}
