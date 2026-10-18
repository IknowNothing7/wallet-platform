package com.waller.wallet_platform.deposit.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static com.waller.wallet_platform.support.TestData.account;
import static com.waller.wallet_platform.support.TestData.testDeposit;
import static com.waller.wallet_platform.support.TestData.user;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import java.util.List;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.deposit.entity.DepositStatus;
import com.waller.wallet_platform.deposit.repository.DepositRepository;
import com.waller.wallet_platform.security.JwtTokenProvider;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import com.waller.wallet_platform.support.IntegrationTest;

import io.restassured.RestAssured;

public class DepositControllerTest extends IntegrationTest {

    @Autowired
    DepositRepository depositRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void getAccountDeposits() {
        // TODO: Same naming problem as AccountControllerTest: "Admin" with role USER.
        AppUser currentUser = appUserRepository.save(user("Admin", "admin@email.com", UserRole.USER));
        Account savedAccount = accountRepository.save(account(currentUser, 0, "USD"));
        depositRepository.saveAll(List.of(
                testDeposit(savedAccount, 100, "USD", DepositStatus.PENDING, "test", "test1"),
                testDeposit(savedAccount, 50, "USD", DepositStatus.PENDING, "test", "test2")));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(currentUser))
                .when().get("/deposit/account/{accountId}", savedAccount.getId())
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("payload.content", hasSize(2))
                .body("payload.content.status", contains("PENDING", "PENDING"))
                .body("payload.content.amount", contains(50, 100));

    }

    // TODO: GET /deposit isn't mapped; this passes only because security rejects every path first.
    //  Point it at a real endpoint, e.g. /deposit/account/{accountId}.
        @Test
    void anonymousIsUnauthorized() {
        given()
        .when()
                .get("/deposit")
        .then()
                .statusCode(401);
    }

    // TODO: Missing endpoint tests:
    //  - POST /deposit/create: 201, the deposit row is saved, and a DepositCreated outbox event is written in the
    //    same transaction (see TransferControllerTest).
    //  - Another user reading /deposit/account/{id} or /deposit/{id} gets 404.
    //  - The publisher polls a missing Kafka broker in the background; set outbox.publisher.poll-interval=PT1H like
    //    TransferControllerTest before adding tests that write outbox events.
}
