package com.waller.wallet_platform.account.controller;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static com.waller.wallet_platform.support.TestData.account;
import static com.waller.wallet_platform.support.TestData.user;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.security.JwtTokenProvider;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;

import io.restassured.RestAssured;

import org.testcontainers.junit.jupiter.Container;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers 
public class AccountControllerTest {

    @Container 
    @ServiceConnection 
    static PostgreSQLContainer postgresql = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort 
    private Integer port;

    @Autowired 
    private AccountRepository accountRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;


    @BeforeEach void setUp() {
        RestAssured.port=port;
        accountRepository.deleteAll();
        // TODO: Users are never deleted. The next test that saves "admin@email.com" will fail on the unique email,
        //  so also call appUserRepository.deleteAll() here, after accounts.
    }

    @Test 
    void getAllAccounts() {
        // TODO: Rename: this user is called "Admin" but has role USER, and the test checks the owner's own accounts.
        AppUser currentUser = appUserRepository.save(user("Admin", "admin@email.com", UserRole.USER));
        // TODO: Also save an account owned by another user and assert it is NOT returned; right now nothing proves
        //  that /account returns only the caller's accounts.
        accountRepository.saveAll(List.of(
                    account(currentUser, 0, "USD"),
                    account(currentUser, 10000, "VND"),
                    account(currentUser, 50, "USD")
        ));

        given()
        .auth().oauth2(jwtTokenProvider.generateToken(currentUser))
        .when().get("/account")
        .then()
        .statusCode(200)
        .body("success", equalTo(true))
        .body("payload", hasSize(3))
        .body("payload.currency", contains("USD", "VND", "USD"))
        .body("payload.balance", contains(0, 10000, 50));
    }

        @Test
    void anonymousIsUnauthorized() {
        given()
        .when()
                .get("/account")
        .then()
                .statusCode(401);
    }

    // TODO: Missing endpoint tests:
    //  - GET /account/{id}: owner gets 200; another user gets 404 (not 403); an admin can read any user account.
    //  - GET /account/{id} for a system account gives 404, even for an admin.
    //  - Account creation: happy path, plus a validation error such as a bad currency.
}
