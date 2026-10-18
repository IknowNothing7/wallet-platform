package com.waller.wallet_platform.bank.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.waller.wallet_platform.bank.dto.BankPayment;
import com.waller.wallet_platform.bank.dto.BankPaymentRequest;
import com.waller.wallet_platform.bank.dto.BankPaymentStatus;

// Against a real HTTP server, so RestClient's own status handling is what's tested
class BankClientTest {

    private static final BankPaymentRequest REQUEST = new BankPaymentRequest(2500, "USD", "7");

    private final CountDownLatch testDone = new CountDownLatch(1);
    private HttpServer server;
    private BankClient client;

    // What POST /payments answers; set per test
    private int status;
    private String body;
    private volatile String receivedIdempotencyKey;
    private volatile String receivedRequest;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/payments", this::respond);
        // Holds the request open until the test ends, so the client's read timeout fires
        server.createContext("/slow", exchange -> {
            try {
                testDone.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // One thread per request, so the held /slow request doesn't block the others
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        client = new BankClient("http://localhost:" + server.getAddress().getPort(), Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() {
        testDone.countDown();
        server.stop(0);
    }

    @Test
    void accepted_returnsPaymentAndSendsIdempotencyKey() {
        status = 202;
        body = """
                {"reference":"bsim_1","amount":2500,"currency":"USD","walletDepositId":"7","status":"PENDING"}""";

        BankPayment payment = client.createPayment("deposit-7", REQUEST);

        assertThat(payment.reference()).isEqualTo("bsim_1");
        assertThat(payment.status()).isEqualTo(BankPaymentStatus.PENDING);
        assertThat(receivedIdempotencyKey).isEqualTo("deposit-7");
    }

    @Test
    void cancelPayment_postsToCancelAndReturnsPayment() {
        status = 200;
        body = """
                {"reference":"bsim_1","amount":2500,"currency":"USD","walletDepositId":"7","status":"CANCELLED"}""";

        BankPayment payment = client.cancelPayment("bsim_1");

        assertThat(payment.status()).isEqualTo(BankPaymentStatus.CANCELLED);
        assertThat(receivedRequest).isEqualTo("POST /payments/bsim_1/cancel");
    }

    @Test
    void clientError_throwsBankRejectedWithStatusAndBody() {
        status = 400;
        body = "{\"message\":\"currency must be a 3-letter ISO code\"}";

        assertThatThrownBy(() -> client.createPayment("deposit-7", REQUEST))
                .isInstanceOfSatisfying(BankRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getResponseBody()).isEqualTo(body);
                });
    }

    @Test
    void serverError_isNotTreatedAsRejection() {
        status = 503;
        body = "{\"message\":\"try again\"}";

        assertThatThrownBy(() -> client.createPayment("deposit-7", REQUEST))
                .isInstanceOf(HttpServerErrorException.class)
                .isNotInstanceOf(BankRejectedException.class);
    }

    @Test
    void timeout_isNotTreatedAsRejection() {
        BankClient slowClient = new BankClient(
                "http://localhost:" + server.getAddress().getPort() + "/slow", Duration.ofMillis(200));

        // Depending on where the read times out RestClient throws ResourceAccessException or a plain
        // RestClientException; what matters is that it's a timeout and not a rejection
        assertThatThrownBy(() -> slowClient.createPayment("deposit-7", REQUEST))
                .isInstanceOf(RestClientException.class)
                .isNotInstanceOf(BankRejectedException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
    }

    private void respond(HttpExchange exchange) throws IOException {
        receivedIdempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        receivedRequest = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
