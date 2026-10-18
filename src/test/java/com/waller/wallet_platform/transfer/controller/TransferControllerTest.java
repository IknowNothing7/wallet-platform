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

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.outbox.entity.OutboxEvent;
import com.waller.wallet_platform.outbox.entity.OutboxStatus;
import com.waller.wallet_platform.outbox.repository.OutboxRepository;
import com.waller.wallet_platform.security.JwtTokenProvider;
import com.waller.wallet_platform.transfer.entity.Transfer;
import com.waller.wallet_platform.transfer.entity.TransferStatus;
import com.waller.wallet_platform.transfer.repository.TransferRepository;
import com.waller.wallet_platform.transfer.service.TransferProcessingService;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import com.waller.wallet_platform.support.IntegrationTest;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;

class TransferControllerTest extends IntegrationTest {

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

    @Autowired
    private TransferProcessingService transferProcessingService;

    private AppUser alice;
    private AppUser bob;
    private Account aliceUsd;
    private Account bobUsd;

    @BeforeEach
    void setUp() {
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

    @Test
    void anonymousIsUnauthorized() {
        given()
        .when()
                .get("/transfer/account/{accountId}", aliceUsd.getId())
        .then()
                .statusCode(401);
    }

    @Test
    void createTransfer_unknownSourceAccount_returns404() {
        long unknownId = 132343213L;
        given()
                .auth().oauth2(jwtTokenProvider.generateToken(alice))
                .contentType(ContentType.JSON)
                .body(transferBody(unknownId, bobUsd.getId(), 250, "USD"))
        .when()
                .post("/transfer/create")
        .then()
                .statusCode(404)
                .body("message", equalTo("Account " + unknownId + " not found"));
    }

    @Test
    void createTransfer_fromSomeoneElsesAccount_returns404AndWritesNothing() {
        given()
                .auth().oauth2(jwtTokenProvider.generateToken(alice))
                .contentType(ContentType.JSON)
                .body(transferBody(bobUsd.getId(), aliceUsd.getId(), 250, "USD"))
        .when()
                .post("/transfer/create")
        .then()
                .statusCode(404)
                .body("message", equalTo("Account " + bobUsd.getId() + " not found"));

        assertThat(transferRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void createTransfer_unknownDestinationAccount_returns404NamingThatAccount() {
        long unknownId = 132343213L;
        given()
                .auth().oauth2(jwtTokenProvider.generateToken(alice))
                .contentType(ContentType.JSON)
                .body(transferBody(aliceUsd.getId(), unknownId, 250, "USD"))
        .when()
                .post("/transfer/create")
        .then()
                .statusCode(404)
                .body("message", equalTo("Account " + unknownId + " not found"));
    }

    @Test
    void createTransfer_toSameAccount_returns400AndWritesNothing() {
        given()
                .auth().oauth2(jwtTokenProvider.generateToken(alice))
                .contentType(ContentType.JSON)
                .body(transferBody(aliceUsd.getId(), aliceUsd.getId(), 250, "USD"))
        .when()
                .post("/transfer/create")
        .then()
                .statusCode(400)
                .body("message", equalTo("You can not transfer to the same account"));

        assertThat(transferRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void createTransfer_requestCurrencyDiffersFromAccounts_returns400AndWritesNothing() {
        given()
                .auth().oauth2(jwtTokenProvider.generateToken(alice))
                .contentType(ContentType.JSON)
                .body(transferBody(aliceUsd.getId(), bobUsd.getId(), 250, "EUR"))
        .when()
                .post("/transfer/create")
        .then()
                .statusCode(400)
                .body("success", equalTo(false));

        assertThat(transferRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void createTransfer_repeatedIdempotencyKey_returnsSameTransferAndCreatesItOnce() {
        Integer first = postWithKey("key-1", 250).statusCode(201).extract().path("payload.id");
        Integer second = postWithKey("key-1", 250).statusCode(201).extract().path("payload.id");

        assertThat(second).isEqualTo(first);
        assertThat(transferRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    @Test
    void createTransfer_idempotencyKeyReusedForDifferentBody_returns422AndCreatesNothingMore() {
        postWithKey("key-1", 250).statusCode(201);

        postWithKey("key-1", 300)
                .statusCode(422)
                .body("success", equalTo(false));

        assertThat(transferRepository.count()).isEqualTo(1);
    }

    @Test
    void reverseTransfer_asAdmin_returns202AndRestoresBalances() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));
        Long id = completedTransfer(500);

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .contentType(ContentType.JSON)
                .body(Map.of("reason", "fraud"))
        .when()
                .post("/transfer/{id}/reverse", id)
        .then()
                .statusCode(202)
                .body("payload.status", equalTo("REVERSED"));

        assertThat(accountRepository.findById(aliceUsd.getId()).orElseThrow().getBalance()).isEqualTo(1000);
        assertThat(accountRepository.findById(bobUsd.getId()).orElseThrow().getBalance()).isZero();
    }

    @Test
    void reverseTransfer_asNonAdmin_returns403AndChangesNothing() {
        Long id = completedTransfer(500);

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(alice))
                .contentType(ContentType.JSON)
                .body(Map.of("reason", "fraud"))
        .when()
                .post("/transfer/{id}/reverse", id)
        .then()
                .statusCode(403);

        assertThat(transferRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(accountRepository.findById(bobUsd.getId()).orElseThrow().getBalance()).isEqualTo(490);
    }

    @Test
    void reverseTransfer_recipientSpentTheMoney_returns409() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));
        Long id = completedTransfer(500);
        Account bobBalance = accountRepository.findById(bobUsd.getId()).orElseThrow();
        bobBalance.setBalance(100L);
        accountRepository.save(bobBalance);

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .contentType(ContentType.JSON)
                .body(Map.of("reason", "fraud"))
        .when()
                .post("/transfer/{id}/reverse", id)
        .then()
                .statusCode(409)
                .body("message", equalTo("Destination account no longer has the funds to reverse this transfer"));

        assertThat(transferRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void reverseTransfer_pendingTransfer_returns409() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));
        Transfer pending = transferRepository.save(transfer(aliceUsd, bobUsd, 500, "USD"));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .contentType(ContentType.JSON)
                .body(Map.of("reason", "fraud"))
        .when()
                .post("/transfer/{id}/reverse", pending.getId())
        .then()
                .statusCode(409)
                .body("message", equalTo("Transfer is PENDING and can not be reversed"));
    }

    @Test
    void reverseTransfer_blankReason_returns400() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));
        Long id = completedTransfer(500);

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .contentType(ContentType.JSON)
                .body(Map.of("reason", " "))
        .when()
                .post("/transfer/{id}/reverse", id)
        .then()
                .statusCode(400)
                .body("payload", hasKey("reason"));

        assertThat(transferRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TransferStatus.COMPLETED);
    }

    private Long completedTransfer(long amount) {
        Transfer saved = transferRepository.save(transfer(aliceUsd, bobUsd, amount, "USD"));
        assertThat(transferProcessingService.process(saved.getId()).status()).isEqualTo(TransferStatus.COMPLETED);
        return saved.getId();
    }

    private io.restassured.response.ValidatableResponse postWithKey(String key, long amount) {
        return given()
                .auth().oauth2(jwtTokenProvider.generateToken(alice))
                .header("Idempotency-Key", key)
                .contentType(ContentType.JSON)
                .body(transferBody(aliceUsd.getId(), bobUsd.getId(), amount, "USD"))
        .when()
                .post("/transfer/create")
        .then();
    }

    private static Map<String, Object> transferBody(Long from, Long to, long amount, String currency) {
        return Map.of("idFromAccount", from, "idToAccount", to, "amount", amount, "currency", currency);
    }

}
