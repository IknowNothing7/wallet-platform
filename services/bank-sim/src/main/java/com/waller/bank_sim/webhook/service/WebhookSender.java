package com.waller.bank_sim.webhook.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;
import com.waller.bank_sim.config.WebhookProperties;
import com.waller.bank_sim.control.dto.PaymentOutcome;
import com.waller.bank_sim.control.dto.Scenario.WebhookMode;
import com.waller.bank_sim.control.service.ScenarioService;
import com.waller.bank_sim.payment.model.Payment;
import com.waller.bank_sim.payment.model.PaymentStatus;
import com.waller.bank_sim.payment.repository.PaymentStore;
import com.waller.bank_sim.webhook.dto.WebhookPayload;

/** Delivers payment results at least once: retries with exponential backoff until wallet-service answers 2xx. */
@Component
@Slf4j
public class WebhookSender {

    private static final String PAYMENT_NOT_FOUND = "Payment not found";
    private static final String PAYMENT_PENDING = "Payment has no result yet";

    private final WebhookProperties properties;
    private final WebhookSigner signer;
    private final ScenarioService scenarioService;
    private final PaymentStore paymentStore;
    private final JsonMapper jsonMapper;
    private final RestClient restClient;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public WebhookSender(
            WebhookProperties properties,
            WebhookSigner signer,
            ScenarioService scenarioService,
            PaymentStore paymentStore,
            JsonMapper jsonMapper) {
        this.properties = properties;
        this.signer = signer;
        this.scenarioService = scenarioService;
        this.paymentStore = paymentStore;
        this.jsonMapper = jsonMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.requestTimeout());
        requestFactory.setReadTimeout(properties.requestTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /** Settles the payment after the configured delay and starts delivering its result. */
    public void scheduleResult(Payment payment, PaymentOutcome outcome) {
        scheduler.schedule(() -> {
            payment.setStatus(outcome.succeeded() ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED);
            payment.setFailureReason(outcome.failureReason());
            WebhookMode mode = scenarioService.get().webhookMode();
            if (mode == WebhookMode.DROP) {
                log.info("Dropping webhook for payment {} (scenario)", payment.getReference());
                return;
            }
            deliver(payment, 1);
            if (mode == WebhookMode.DUPLICATE) {
                deliver(payment, 1);
            }
        }, properties.delay().toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Manual resend from the control API, e.g. after a DROP scenario. */
    public void resend(String reference) {
        Payment payment = paymentStore.findByReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, PAYMENT_NOT_FOUND));
        if (payment.getStatus() == PaymentStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, PAYMENT_PENDING);
        }
        scheduler.execute(() -> deliver(payment, 1));
    }

    private void deliver(Payment payment, int attempt) {
        payment.setWebhookAttempts(payment.getWebhookAttempts() + 1);
        try {
            post(toPayload(payment));
            payment.setWebhookDeliveredAt(Instant.now());
            log.info("Delivered webhook for payment {} (attempt {})", payment.getReference(), attempt);
        } catch (RuntimeException e) {
            if (attempt >= properties.maxAttempts()) {
                log.error("Giving up on webhook for payment {} after {} attempts", payment.getReference(), attempt, e);
                return;
            }
            Duration delay = backoff(attempt);
            log.warn("Webhook for payment {} failed (attempt {}/{}), retrying in {}: {}",
                    payment.getReference(), attempt, properties.maxAttempts(), delay, e.getMessage());
            scheduler.schedule(() -> deliver(payment, attempt + 1), delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    // Throws on any non-2xx status, which counts as a failed attempt
    private void post(WebhookPayload payload) {
        String body = jsonMapper.writeValueAsString(payload);
        long timestamp = Instant.now().getEpochSecond();
        restClient.post()
                .uri(properties.url())
                .contentType(MediaType.APPLICATION_JSON)
                .header(WebhookSigner.TIMESTAMP_HEADER, String.valueOf(timestamp))
                .header(WebhookSigner.SIGNATURE_HEADER, signer.sign(timestamp, body))
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private Duration backoff(int attempt) {
        Duration delay = properties.initialBackoff().multipliedBy(1L << Math.min(attempt - 1, 20));
        return delay.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : delay;
    }

    private static WebhookPayload toPayload(Payment payment) {
        return new WebhookPayload(
                payment.getReference(),
                payment.getWalletDepositId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getFailureReason(),
                Instant.now());
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
