package com.waller.wallet_platform.user.controller;

import java.util.List;

import com.waller.wallet_platform.user.dto.AppUserRequest;
import com.waller.wallet_platform.user.mapper.AppUserMapper;
import io.restassured.http.ContentType;
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
import static com.waller.wallet_platform.support.TestData.user;
import static org.hamcrest.Matchers.*;

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


    @Autowired
    private AppUserMapper mapper;

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

    @Test
    void createUserAndNoPasswordInResponse() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));
        AppUserRequest request = new AppUserRequest();
        request.setName("newUser");
        request.setPassword("passsworD!11");
        request.setEmail("newOne@email.com");

                given()
                        .contentType(ContentType.JSON)
                        .body(request)
                        .auth().oauth2(jwtTokenProvider.generateToken(admin))
                        .when()
                        .post("/create")
                        .then()
                        .body("success", equalTo(true))
                        .body("payload.name", equalTo(request.getName()))
                        .body("payload.email", equalTo(request.getEmail()))
                        .body("payload.password",empty())
                        .statusCode(200)
                        .body("payload.content.password",empty());
    }

    @Test
    void duplicateEmailIsForbidden() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));
        AppUserRequest request2 = new AppUserRequest();
        request2.setEmail("admin@email.com");
        request2.setPassword("!Password!11");
        request2.setName("Robin");

        given()
                .contentType(ContentType.JSON)
                .body(request2)
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .when()
                .post("/create")
                .then()
                .statusCode(409);}

    // TODO: Missing endpoint tests:
    //  - GET /user paging (page/size) and @Max(100) on size give 400 with the parameter name.
    //  - UserSearchRequest / UserSortField are never used in main code; either wire them into GET /user and test
    //    them, or delete them.
}
