package com.waller.wallet_platform.transfer.controller;

import static com.waller.wallet_platform.support.TestData.account;
import static com.waller.wallet_platform.support.TestData.transfer;
import static com.waller.wallet_platform.support.TestData.user;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.outbox.entity.OutboxEvent;
import com.waller.wallet_platform.outbox.entity.OutboxStatus;
import com.waller.wallet_platform.outbox.repository.OutboxRepository;
import com.waller.wallet_platform.security.JwtTokenProvider;
import com.waller.wallet_platform.transfer.entity.Transfer;
import com.waller.wallet_platform.transfer.entity.TransferStatus;
import com.waller.wallet_platform.transfer.repository.TransferRepository;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;

// No Kafka here: poll only once at startup so the publisher doesn't hold outbox row locks while it waits for a broker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "outbox.publisher.poll-interval=PT1H")
@Testcontainers
class TransferControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgresql = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    private Integer port;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private AppUser alice;
    private AppUser bob;
    private Account aliceUsd;
    private Account bobUsd;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        transferRepository.deleteAll();
        outboxRepository.deleteAll();
        accountRepository.deleteAll();
        appUserRepository.deleteAll();

        alice = appUserRepository.save(user("Alice", "alice@email.com", UserRole.USER));
        bob = appUserRepository.save(user("Bob", "bob@email.com", UserRole.USER));
        aliceUsd = accountRepository.save(account(alice, 1000, "USD"));
        bobUsd = accountRepository.save(account(bob, 0, "USD"));
    }

    @Test
    void createTransfer_persistsTransferAndOutboxEventTogether() {
        Integer id = given()
                .auth().oauth2(jwtTokenProvider.generateToken(alice))
                .contentType(ContentType.JSON)
                .body(transferBody(aliceUsd.getId(), bobUsd.getId(), 250, "USD"))
        .when()
                .post("/transfer/create")
        .then()
                .statusCode(201)
                .body("success", equalTo(true))
                .body("payload.fromAccountId", equalTo(aliceUsd.getId().intValue()))
                .body("payload.toAccountId", equalTo(bobUsd.getId().intValue()))
                .body("payload.amount", equalTo(250))
                .body("payload.status", equalTo("PENDING"))
                .extract().path("payload.id");

        Transfer saved = transferRepository.findById(id.longValue()).orElseThrow();
        assertThat(saved.getAmount()).isEqualTo(250);
        assertThat(saved.getStatus()).isEqualTo(TransferStatus.PENDING);

        List<OutboxEvent> events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        OutboxEvent event = events.getFirst();
        assertThat(event.getAggregateType()).isEqualTo("transfer");
        assertThat(event.getAggregateId()).isEqualTo(id.longValue());
        assertThat(event.getEventType()).isEqualTo("TransferCreated");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getPayload().get("amount").asLong()).isEqualTo(250);
        assertThat(event.getPayload().get("toAccountId").asLong()).isEqualTo(bobUsd.getId());
    }

    @Test
    void createTransfer_currencyMismatch_returns400AndWritesNothing() {
        Account bobEur = accountRepository.save(account(bob, 0, "EUR"));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(alice))
                .contentType(ContentType.JSON)
                .body(transferBody(aliceUsd.getId(), bobEur.getId(), 250, "USD"))
        .when()
                .post("/transfer/create")
        .then()
                .statusCode(400)
                .body("success", equalTo(false));

        assertThat(transferRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void createTransfer_invalidBody_returnsFieldErrors() {
        given()
                .auth().oauth2(jwtTokenProvider.generateToken(alice))
                .contentType(ContentType.JSON)
                .body(transferBody(aliceUsd.getId(), bobUsd.getId(), -5, "usd"))
        .when()
                .post("/transfer/create")
        .then()
                .statusCode(400)
                .body("payload", hasKey("amount"))
                .body("payload", hasKey("currency"));
    }

    @Test
    void getTransfer_visibleToReceiverButNotToStranger() {
        Transfer saved = transferRepository.save(transfer(aliceUsd, bobUsd, 100, "USD"));
        AppUser stranger = appUserRepository.save(user("Eve", "eve@email.com", UserRole.USER));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(bob))
        .when()
                .get("/transfer/{id}", saved.getId())
        .then()
                .statusCode(200)
                .body("payload.id", equalTo(saved.getId().intValue()));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(stranger))
        .when()
                .get("/transfer/{id}", saved.getId())
        .then()
                .statusCode(404)
                .body("message", equalTo("Transfer not found"));
    }

    @Test
    void getAccountTransfers_returnsIncomingAndOutgoingNewestFirst() {
        Account carolUsd = accountRepository.save(account(
                appUserRepository.save(user("Carol", "carol@email.com", UserRole.USER)), 0, "USD"));
        transferRepository.save(transfer(aliceUsd, bobUsd, 100, "USD"));
        transferRepository.save(transfer(bobUsd, aliceUsd, 30, "USD"));
        // Doesn't involve Alice's account
        transferRepository.save(transfer(bobUsd, carolUsd, 7, "USD"));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(alice))
        .when()
                .get("/transfer/account/{accountId}", aliceUsd.getId())
        .then()
                .statusCode(200)
                .body("payload.content.amount", contains(30, 100))
                .body("payload.totalElements", equalTo(2));
    }

    // TODO: Add these once createTransfer is fixed:
    //    a transfer from someone else's account.
    //  - An unknown destination account gives a 404 naming that account; the message currently uses the source id.
    @Test
    void anonymousIsUnauthorized() {
        given()
        .when()
                .get("/transfer/account/{accountId}", aliceUsd.getId())
        .then()
                .statusCode(401);
    }

    @Test
    void userDoesntOwnAccount() {
        long accountId= 132343213L;
        given()
                .auth().oauth2(jwtTokenProvider.generateToken(alice))
                .contentType(ContentType.JSON)
                .body(transferBody(accountId, bobUsd.getId(), 250, "USD"))
                .when()
                .post("/transfer/create")
                .then()
                .statusCode(404)
                .body("message", equalTo("Account " + accountId + " not found"));
    }

    @Test
    void transferFromTheSameAccountAsTarget() {
        given()
                .auth().oauth2(jwtTokenProvider.generateToken(alice))
                .contentType(ContentType.JSON)
                .body(transferBody(alice.getId(), alice.getId(), 250, "USD"))
                .when()
                .post("/transfer/create")
                .then()
                .statusCode(400);
    }

    @Test
    void transferWithDifferentCurrency() {
        given()
                .auth().oauth2(jwtTokenProvider.generateToken(alice))
                .contentType(ContentType.JSON)
                .body(transferBody(alice.getId(), bobUsd.getId(), 250, "ES"))
                .when()
                .post("/transfer/create")
                .then()
                .statusCode(400);
    }

    private static Map<String, Object> transferBody(Long from, Long to, long amount, String currency) {
        return Map.of("idFromAccount", from, "idToAccount", to, "amount", amount, "currency", currency);
    }

}
