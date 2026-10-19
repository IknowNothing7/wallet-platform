package com.waller.bank_sim.webhook.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
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
    private static final String TOO_MANY_RESENDS = "Too many resends for this payment, try again later";

    private final WebhookProperties properties;
    private final WebhookSigner signer;
    private final ScenarioService scenarioService;
    private final PaymentStore paymentStore;
    private final JsonMapper jsonMapper;
    private final RestClient restClient;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    // Carries the trace of the payment request onto the scheduler thread, so the webhook joins that trace
    private final ContextSnapshotFactory contextSnapshots = ContextSnapshotFactory.builder().build();
    private final RateLimiter resendLimiter;

    public WebhookSender(
            WebhookProperties properties,
            WebhookSigner signer,
            ScenarioService scenarioService,
            PaymentStore paymentStore,
            JsonMapper jsonMapper,
            @Value("${bank-sim.webhook.resend.max-requests:5}") int maxResends,
            @Value("${bank-sim.webhook.resend.window:PT15M}") Duration resendWindow,
            ObservationRegistry observationRegistry) {
        this.properties = properties;
        this.signer = signer;
        this.scenarioService = scenarioService;
        this.paymentStore = paymentStore;
        this.jsonMapper = jsonMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.requestTimeout());
        requestFactory.setReadTimeout(properties.requestTimeout());
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .build();
        this.resendLimiter = new RateLimiter(maxResends, resendWindow);
        scheduler.scheduleAtFixedRate(resendLimiter::evictExpired,
                resendWindow.toMillis(), resendWindow.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void scheduleResult(Payment payment, PaymentOutcome outcome) {
        schedule(() -> {
            PaymentStatus status = outcome.succeeded() ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED;
            if (!payment.settle(status, outcome.failureReason())) {
                log.info("Not settling payment {}: already {}", payment.getReference(), payment.getStatus());
                return;
            }
            WebhookMode mode = scenarioService.get().webhookMode();
            if (mode == WebhookMode.DROP) {
                log.info("Dropping webhook for payment {} (scenario)", payment.getReference());
                return;
            }
            deliver(payment, 1);
            if (mode == WebhookMode.DUPLICATE) {
                deliver(payment, 1);
            }
        }, properties.delay());
    }

    public void resend(String reference) {
        Payment payment = paymentStore.findByReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, PAYMENT_NOT_FOUND));
        if (payment.getStatus() == PaymentStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, PAYMENT_PENDING);
        }
        if (!resendLimiter.tryAcquire(reference)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_RESENDS);
        }
        schedule(() -> deliver(payment, 1), Duration.ZERO);
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
            schedule(() -> deliver(payment, attempt + 1), delay);
        }
    }

    private void schedule(Runnable task, Duration delay) {
        scheduler.schedule(contextSnapshots.captureAll().wrap(task), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

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
