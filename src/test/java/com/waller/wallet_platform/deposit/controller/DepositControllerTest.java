package com.waller.wallet_platform.deposit.controller;

import static com.waller.wallet_platform.support.TestData.account;
import static com.waller.wallet_platform.support.TestData.deposit;
import static com.waller.wallet_platform.support.TestData.user;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.deposit.entity.Deposit;
import com.waller.wallet_platform.deposit.entity.DepositStatus;
import com.waller.wallet_platform.deposit.repository.DepositRepository;
import com.waller.wallet_platform.outbox.entity.OutboxEvent;
import com.waller.wallet_platform.outbox.entity.OutboxStatus;
import com.waller.wallet_platform.outbox.repository.OutboxRepository;
import com.waller.wallet_platform.security.JwtTokenProvider;
import com.waller.wallet_platform.support.IntegrationTest;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;

import io.restassured.http.ContentType;

class DepositControllerTest extends IntegrationTest {

    @Autowired
    private DepositRepository depositRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private AppUser owner;
    private AppUser another;
    private Account ownerUsd;

    @BeforeEach
    void setUp() {
        owner = appUserRepository.save(user("user", "user@email.com", UserRole.USER));
        another = appUserRepository.save(user("another", "another@email.com", UserRole.USER));
        ownerUsd = accountRepository.save(account(owner, 0, "USD"));
    }

    @Test
    void getAccountDeposits() {
        depositRepository.saveAll(List.of(
                deposit(ownerUsd, 100, "USD", DepositStatus.PENDING, "test", "test1"),
                deposit(ownerUsd, 50, "USD", DepositStatus.PENDING, "test", "test2")));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(owner))
        .when()
                .get("/deposit/account/{accountId}", ownerUsd.getId())
        .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("payload.content", hasSize(2))
                .body("payload.content.status", contains("PENDING", "PENDING"))
                .body("payload.content.amount", contains(50, 100));
    }

    @Test
    void getAccountDeposits_anotherUserGets404() {
        depositRepository.save(deposit(ownerUsd, 100, "USD", DepositStatus.PENDING, "test", "test1"));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(another))
        .when()
                .get("/deposit/account/{accountId}", ownerUsd.getId())
        .then()
                .statusCode(404)
                .body("success", equalTo(false))
                .body("message", equalTo("Account not found"));
    }

    @Test
    void getDeposit_visibleToOwnerButNotToAnotherUser() {
        Deposit saved = depositRepository.save(deposit(ownerUsd, 100, "USD", DepositStatus.PENDING, "test", "test1"));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(owner))
        .when()
                .get("/deposit/{id}", saved.getId())
        .then()
                .statusCode(200)
                .body("payload.id", equalTo(saved.getId().intValue()))
                .body("payload.amount", equalTo(100));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(another))
        .when()
                .get("/deposit/{id}", saved.getId())
        .then()
                .statusCode(404)
                .body("success", equalTo(false))
                .body("message", equalTo("Deposit not found"));
    }

    @Test
    void anonymousIsUnauthorized() {
        given()
        .when()
                .get("/deposit/account/{accountId}", ownerUsd.getId())
        .then()
                .statusCode(401);
    }

    @Test
    void createDeposit_persistsDepositAndOutboxEventTogether() {
        Integer id = given()
                .auth().oauth2(jwtTokenProvider.generateToken(owner))
                .contentType(ContentType.JSON)
                .body(depositBody(ownerUsd.getId(), 250, "USD"))
        .when()
                .post("/deposit/create")
        .then()
                .statusCode(201)
                .body("success", equalTo(true))
                .body("payload.accountId", equalTo(ownerUsd.getId().intValue()))
                .body("payload.amount", equalTo(250))
                .body("payload.currency", equalTo("USD"))
                .body("payload.status", equalTo("PENDING"))
                .extract().path("payload.id");

        // No bank runs here, so the after-commit submission fails and the deposit stays PENDING
        Deposit saved = depositRepository.findById(id.longValue()).orElseThrow();
        assertThat(saved.getAmount()).isEqualTo(250);
        assertThat(saved.getCurrency()).isEqualTo("USD");
        assertThat(saved.getStatus()).isEqualTo(DepositStatus.PENDING);
        assertThat(saved.getExpiresAt()).isNotNull();

        List<OutboxEvent> events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        OutboxEvent event = events.getFirst();
        assertThat(event.getAggregateType()).isEqualTo("deposit");
        assertThat(event.getAggregateId()).isEqualTo(id.longValue());
        assertThat(event.getEventType()).isEqualTo("DepositCreated");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getPayload().get("amount").asLong()).isEqualTo(250);
        assertThat(event.getPayload().get("accountId").asLong()).isEqualTo(ownerUsd.getId());
    }

    @Test
    void createDeposit_intoAnotherUsersAccount_is404AndSavesNothing() {
        given()
                .auth().oauth2(jwtTokenProvider.generateToken(another))
                .contentType(ContentType.JSON)
                .body(depositBody(ownerUsd.getId(), 250, "USD"))
        .when()
                .post("/deposit/create")
        .then()
                .statusCode(404)
                .body("success", equalTo(false))
                .body("message", equalTo("Account " + ownerUsd.getId() + " not found"));

        assertThat(depositRepository.findAll()).isEmpty();
        assertThat(outboxRepository.findAll()).isEmpty();
    }

    private static Map<String, Object> depositBody(Long accountId, long amount, String currency) {
        return Map.of("accountId", accountId, "amount", amount, "currency", currency, "gateway", "bank-sim");
    }
}
