package com.waller.bank_sim.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.sun.net.httpserver.HttpServer;
import com.waller.bank_sim.config.WebhookProperties;
import com.waller.bank_sim.control.dto.PaymentOutcome;
import com.waller.bank_sim.control.dto.Scenario;
import com.waller.bank_sim.control.dto.Scenario.WebhookMode;
import com.waller.bank_sim.control.service.ScenarioService;
import com.waller.bank_sim.payment.model.Payment;
import com.waller.bank_sim.payment.model.PaymentStatus;
import com.waller.bank_sim.payment.repository.PaymentStore;

import tools.jackson.databind.json.JsonMapper;

class WebhookSenderTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_RESENDS = 2;
    private static final String SECRET = "test-secret";
    // How long to wait before concluding that no (further) webhook is coming
    private static final long QUIET_MILLIS = 300;

    private record Received(String body, String timestamp, String signature) {
    }

    // Stands in for wallet-service: records every webhook and answers with the queued statuses, then 200
    private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
    private final ConcurrentLinkedQueue<Integer> responses = new ConcurrentLinkedQueue<>();
    private HttpServer server;

    private final ScenarioService scenarioService = new ScenarioService();
    private final PaymentStore paymentStore = new PaymentStore();
    private WebhookSigner signer;
    private WebhookSender sender;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/webhook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(body,
                    exchange.getRequestHeaders().getFirst(WebhookSigner.TIMESTAMP_HEADER),
                    exchange.getRequestHeaders().getFirst(WebhookSigner.SIGNATURE_HEADER)));
            Integer status = responses.poll();
            exchange.sendResponseHeaders(status == null ? 200 : status, -1);
            exchange.close();
        });
        server.start();

        WebhookProperties properties = new WebhookProperties(
                "http://localhost:" + server.getAddress().getPort() + "/webhook", SECRET,
                Duration.ZERO, MAX_ATTEMPTS, Duration.ofMillis(10), Duration.ofMillis(20), Duration.ofSeconds(2));
        signer = new WebhookSigner(properties);
        sender = new WebhookSender(properties, signer, scenarioService, paymentStore,
                JsonMapper.builder().build(), MAX_RESENDS, Duration.ofMinutes(15));
    }

    @AfterEach
    void tearDown() {
        sender.shutdown();
        server.stop(0);
    }

    @Test
    void scheduleResult_settlesPaymentAndDeliversSignedWebhook() throws InterruptedException {
        Payment payment = payment("bsim_1");

        sender.scheduleResult(payment, PaymentOutcome.success());

        Received webhook = next();
        assertThat(webhook.body()).contains("\"reference\":\"bsim_1\"", "\"status\":\"SUCCEEDED\"");
        assertThat(webhook.signature()).isEqualTo(signer.sign(Long.parseLong(webhook.timestamp()), webhook.body()));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertNoMoreWebhooks();
        assertThat(payment.getWebhookAttempts()).isEqualTo(1);
        assertThat(payment.getWebhookDeliveredAt()).isNotNull();
    }

    @Test
    void scheduleResult_declined_deliversFailureReason() throws InterruptedException {
        Payment payment = payment("bsim_1");

        sender.scheduleResult(payment, PaymentOutcome.declined("Declined by issuer"));

        assertThat(next().body()).contains("\"status\":\"FAILED\"", "\"failureReason\":\"Declined by issuer\"");
    }

    @Test
    void failedDelivery_isRetriedUntil2xx() throws InterruptedException {
        responses.add(500);
        responses.add(503);
        Payment payment = payment("bsim_1");

        sender.scheduleResult(payment, PaymentOutcome.success());

        next();
        next();
        next();
        assertNoMoreWebhooks();
        assertThat(payment.getWebhookAttempts()).isEqualTo(3);
        assertThat(payment.getWebhookDeliveredAt()).isNotNull();
    }

    @Test
    void failedDelivery_givesUpAfterMaxAttempts() throws InterruptedException {
        for (int i = 0; i < MAX_ATTEMPTS + 1; i++) {
            responses.add(500);
        }
        Payment payment = payment("bsim_1");

        sender.scheduleResult(payment, PaymentOutcome.success());

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            next();
        }
        assertNoMoreWebhooks();
        assertThat(payment.getWebhookAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(payment.getWebhookDeliveredAt()).isNull();
    }

    @Test
    void dropMode_settlesButSendsNothing() throws InterruptedException {
        scenarioService.set(scenario(WebhookMode.DROP));
        Payment payment = payment("bsim_1");

        sender.scheduleResult(payment, PaymentOutcome.success());

        assertNoMoreWebhooks();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void duplicateMode_deliversTheSameResultTwice() throws InterruptedException {
        scenarioService.set(scenario(WebhookMode.DUPLICATE));
        Payment payment = payment("bsim_1");

        sender.scheduleResult(payment, PaymentOutcome.success());

        assertThat(next().body()).contains("\"reference\":\"bsim_1\"");
        assertThat(next().body()).contains("\"reference\":\"bsim_1\"");
        assertNoMoreWebhooks();
    }

    @Test
    void cancelledBeforeResult_sendsNothing() throws InterruptedException {
        Payment payment = payment("bsim_1");
        payment.settle(PaymentStatus.CANCELLED, "Cancelled by merchant");

        sender.scheduleResult(payment, PaymentOutcome.success());

        assertNoMoreWebhooks();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void resend_settledPayment_deliversAgain() throws InterruptedException {
        Payment payment = payment("bsim_1");
        payment.settle(PaymentStatus.SUCCEEDED, null);

        sender.resend("bsim_1");

        assertThat(next().body()).contains("\"reference\":\"bsim_1\"");
    }

    @Test
    void resend_pendingPayment_returns409() {
        payment("bsim_1");

        assertStatus(() -> sender.resend("bsim_1"), HttpStatus.CONFLICT);
    }

    @Test
    void resend_unknownReference_returns404() {
        assertStatus(() -> sender.resend("bsim_missing"), HttpStatus.NOT_FOUND);
    }

    @Test
    void resend_overLimit_returns429PerReference() throws InterruptedException {
        payment("bsim_1").settle(PaymentStatus.SUCCEEDED, null);
        payment("bsim_2").settle(PaymentStatus.SUCCEEDED, null);

        for (int i = 0; i < MAX_RESENDS; i++) {
            sender.resend("bsim_1");
        }
        assertStatus(() -> sender.resend("bsim_1"), HttpStatus.TOO_MANY_REQUESTS);
        sender.resend("bsim_2");

        for (int i = 0; i < MAX_RESENDS + 1; i++) {
            next();
        }
        assertNoMoreWebhooks();
    }

    private Payment payment(String reference) {
        return paymentStore.getOrCreate("key-" + reference,
                key -> new Payment(reference, key, 1000L, "USD", "dep-1"));
    }

    private Received next() throws InterruptedException {
        Received webhook = received.poll(5, TimeUnit.SECONDS);
        assertThat(webhook).as("webhook received").isNotNull();
        return webhook;
    }

    private void assertNoMoreWebhooks() throws InterruptedException {
        assertThat(received.poll(QUIET_MILLIS, TimeUnit.MILLISECONDS)).as("unexpected webhook").isNull();
    }

    private static Scenario scenario(WebhookMode mode) {
        Scenario happy = Scenario.happyPath();
        return new Scenario(happy.outcome(), happy.declineRate(), happy.latency(),
                happy.failureRate(), happy.lostResponseRate(), mode);
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }
}
