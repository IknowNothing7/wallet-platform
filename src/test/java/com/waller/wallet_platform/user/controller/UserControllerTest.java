package com.waller.wallet_platform.user.controller;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.waller.wallet_platform.security.JwtTokenProvider;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;

import io.restassured.RestAssured;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static com.waller.wallet_platform.support.TestData.user;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class UserControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgresql = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    private Integer port;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        appUserRepository.deleteAll();
    }

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

    // TODO: Missing endpoint tests:
    //  - POST /user/create: 201; the password hash never appears in the response; a duplicate email in different
    //    case gives 409.
    //  - GET /user paging (page/size) and @Max(100) on size give 400 with the parameter name.
    //  - UserSearchRequest / UserSortField are never used in main code; either wire them into GET /user and test
    //    them, or delete them.
}
