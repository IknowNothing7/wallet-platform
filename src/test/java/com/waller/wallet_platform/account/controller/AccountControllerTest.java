package com.waller.wallet_platform.account.controller;

import static com.waller.wallet_platform.support.TestData.account;
import static com.waller.wallet_platform.support.TestData.user;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.waller.wallet_platform.account.entity.Account;
import com.waller.wallet_platform.account.entity.AccountStatus;
import com.waller.wallet_platform.account.entity.AccountType;
import com.waller.wallet_platform.account.entity.SystemAccountKind;
import com.waller.wallet_platform.account.repository.AccountRepository;
import com.waller.wallet_platform.account.service.SystemAccountService;
import com.waller.wallet_platform.outbox.entity.OutboxEvent;
import com.waller.wallet_platform.outbox.repository.OutboxRepository;
import com.waller.wallet_platform.security.JwtTokenProvider;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import com.waller.wallet_platform.support.IntegrationTest;

import io.restassured.http.ContentType;

class AccountControllerTest extends IntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private SystemAccountService systemAccountService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void getMyAccounts_returnsOnlyTheCallersAccounts() {
        AppUser currentUser = appUserRepository.save(user("user", "user@email.com", UserRole.USER));
        AppUser anotherUser = appUserRepository.save(user("another", "another@email.com", UserRole.USER));
        accountRepository.saveAll(List.of(
                account(currentUser, 0, "USD"),
                account(currentUser, 10000, "VND"),
                account(anotherUser, 999, "EUR"),
                account(currentUser, 50, "EUR")));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(currentUser))
        .when()
                .get("/api/accounts")
        .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("payload", hasSize(3))
                .body("payload.currency", contains("USD", "VND", "EUR"))
                .body("payload.balance", contains(0, 10000, 50))
                .body("payload.userId", everyItem(equalTo(currentUser.getId().intValue())));
    }

    @Test
    void anonymousIsUnauthorized() {
        given()
        .when()
                .get("/api/accounts")
        .then()
                .statusCode(401);
    }

    @Test
    void getAccount_ownerGetsIt() {
        AppUser owner = appUserRepository.save(user("user", "user@email.com", UserRole.USER));
        Account account = accountRepository.save(account(owner, 0, "USD"));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(owner))
        .when()
                .get("/api/accounts/" + account.getId())
        .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("payload.id", equalTo(account.getId().intValue()))
                .body("payload.currency", equalTo("USD"))
                .body("payload.balance", equalTo(0))
                .body("payload.userId", equalTo(owner.getId().intValue()));
    }

    @Test
    void getAccount_adminCanReadAnyUsersAccount() {
        AppUser owner = appUserRepository.save(user("user", "user@email.com", UserRole.USER));
        AppUser admin = appUserRepository.save(user("admin", "admin@email.com", UserRole.ADMIN));
        Account account = accountRepository.save(account(owner, 0, "USD"));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
        .when()
                .get("/api/accounts/" + account.getId())
        .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("payload.id", equalTo(account.getId().intValue()))
                .body("payload.userId", equalTo(owner.getId().intValue()));
    }

    @Test
    void getAccount_anotherUserGets404NotForbidden() {
        AppUser owner = appUserRepository.save(user("user", "user@email.com", UserRole.USER));
        AppUser another = appUserRepository.save(user("another", "another@email.com", UserRole.USER));
        Account account = accountRepository.save(account(owner, 0, "USD"));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(another))
        .when()
                .get("/api/accounts/" + account.getId())
        .then()
                .statusCode(404)
                .body("success", equalTo(false));
    }

    @Test
    void getAccount_systemAccountIs404EvenForAdmin() {
        AppUser admin = appUserRepository.save(user("admin", "admin@email.com", UserRole.ADMIN));
        Account systemAccount = systemAccountService.get(SystemAccountKind.FEES, "USD");

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
        .when()
                .get("/api/accounts/" + systemAccount.getId())
        .then()
                .statusCode(404)
                .body("success", equalTo(false));
    }

    @Test
    void createAccount_persistsAccountAndOutboxEvent() {
        AppUser owner = appUserRepository.save(user("user", "user@email.com", UserRole.USER));

        Integer id = given()
                .auth().oauth2(jwtTokenProvider.generateToken(owner))
                .contentType(ContentType.JSON)
                .body(Map.of("currency", "USD"))
        .when()
                .post("/api/accounts")
        .then()
                .statusCode(201)
                .body("success", equalTo(true))
                .body("payload.currency", equalTo("USD"))
                .body("payload.balance", equalTo(0))
                .body("payload.type", equalTo("USER"))
                .body("payload.status", equalTo("ACTIVE"))
                .body("payload.userId", equalTo(owner.getId().intValue()))
                .extract().path("payload.id");

        Account saved = accountRepository.findById(id.longValue()).orElseThrow();
        assertThat(saved.getCurrency()).isEqualTo("USD");
        assertThat(saved.getBalance()).isZero();
        assertThat(saved.getType()).isEqualTo(AccountType.USER);
        assertThat(saved.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        List<OutboxEvent> events = outboxRepository.findAll();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getAggregateType()).isEqualTo("account");
            assertThat(event.getAggregateId()).isEqualTo(id.longValue());
            assertThat(event.getEventType()).isEqualTo("AccountCreated");
        });
    }

    @Test
    void createAccount_anonymousIsUnauthorized() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("currency", "USD"))
        .when()
                .post("/api/accounts")
        .then()
                .statusCode(401);

        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void createAccount_badCurrencyIsRejected() {
        AppUser owner = appUserRepository.save(user("user", "user@email.com", UserRole.USER));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(owner))
                .contentType(ContentType.JSON)
                .body(Map.of("currency", "usd"))
        .when()
                .post("/api/accounts")
        .then()
                .statusCode(400)
                .body("success", equalTo(false))
                .body("message", equalTo("Validation failed"))
                .body("payload.currency", equalTo("Currency must be a 3-letter ISO code"));

        assertThat(accountRepository.count()).isZero();
    }
}
