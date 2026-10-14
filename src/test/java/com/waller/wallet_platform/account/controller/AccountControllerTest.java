package com.waller.wallet_platform.account.controller;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.AccountStatus;
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
    }

    @Test 
    void getAllAccounts() {
        AppUser currentUser = appUserRepository.save(user("Admin", "admin@email.com", UserRole.USER));
        accountRepository.saveAll(List.of(
                    newAccount(0, "USD", AccountStatus.ACTIVE, currentUser),
                    newAccount(10000, "VND", AccountStatus.ACTIVE, currentUser),
                    newAccount(50, "USD", AccountStatus.ACTIVE, currentUser)

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

       private static AppUser user(String name, String email, UserRole role) {
        return AppUser.builder().name(name).email(email).passwordHash("unused").role(role).build();
    }

        private static Account newAccount(int balance, String currency, AccountStatus status, AppUser user) {
            Account acc = new Account();
            acc.setBalance((long) balance);
            acc.setCurrency(currency);
            acc.setStatus(status);
            acc.setUser(user);

            return acc;
        }
}
