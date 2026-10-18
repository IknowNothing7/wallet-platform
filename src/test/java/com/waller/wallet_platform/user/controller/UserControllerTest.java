package com.waller.wallet_platform.user.controller;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.waller.wallet_platform.security.JwtTokenProvider;
import com.waller.wallet_platform.user.dto.AppUserRequest;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import com.waller.wallet_platform.support.IntegrationTest;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static com.waller.wallet_platform.support.TestData.user;

class UserControllerTest extends IntegrationTest {

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void adminGetsAllUsers() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));
        appUserRepository.saveAll(List.of(
                user("Alice", "alice@email.com", UserRole.USER),
                user("Bob", "bob@email.com", UserRole.USER)));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
        .when()
                .get("/user")
        .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("payload.totalElements", equalTo(3))
                .body("payload.content", hasSize(3))
                .body("payload.content.email", contains("admin@email.com", "alice@email.com", "bob@email.com"));
    }

    @Test
    void regularUserIsForbidden() {
        AppUser alice = appUserRepository.save(user("Alice", "alice@email.com", UserRole.USER));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(alice))
        .when()
                .get("/user")
        .then()
                .statusCode(403);
    }

    @Test
    void anonymousIsUnauthorized() {
        given()
        .when()
                .get("/user")
        .then()
                .statusCode(401);
    }

    @Test
    void createUserReturnsCreatedWithoutPassword() {
        AppUserRequest request = AppUserRequest.builder()
                .name("newUser")
                .email("newOne@email.com")
                .password("passsworD!11")
                .build();

        given()
                .contentType(ContentType.JSON)
                .body(request)
        .when()
                .post("/user/create")
        .then()
                .statusCode(201)
                .body("success", equalTo(true))
                .body("payload.name", equalTo("newUser"))
                .body("payload.email", equalTo("newone@email.com"))
                .body("payload", not(hasKey("password")))
                .body("payload", not(hasKey("passwordHash")));
    }

    @Test
    void duplicateEmailInDifferentCaseIsConflict() {
        appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));
        AppUserRequest request = AppUserRequest.builder()
                .name("Robin")
                .email("ADMIN@email.com")
                .password("!Password!11")
                .build();

        given()
                .contentType(ContentType.JSON)
                .body(request)
        .when()
                .post("/user/create")
        .then()
                .statusCode(409);
    }

    // TODO: Missing endpoint tests:
    //  - GET /user paging (page/size) and @Max(100) on size give 400 with the parameter name.
    //  - UserSearchRequest / UserSortField are never used in main code; either wire them into GET /user and test
    //    them, or delete them.
}
