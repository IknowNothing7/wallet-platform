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

    @Test
    void usersArePagedById() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));
        appUserRepository.saveAll(List.of(
                user("Alice", "alice@email.com", UserRole.USER),
                user("Bob", "bob@email.com", UserRole.USER)));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .queryParam("page", 1)
                .queryParam("size", 2)
        .when()
                .get("/user")
        .then()
                .statusCode(200)
                .body("payload.page", equalTo(1))
                .body("payload.size", equalTo(2))
                .body("payload.totalElements", equalTo(3))
                .body("payload.totalPages", equalTo(2))
                .body("payload.content.email", contains("bob@email.com"));
    }

    @Test
    void pageSizeOver100IsBadRequest() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .queryParam("size", 101)
        .when()
                .get("/user")
        .then()
                .statusCode(400)
                .body("success", equalTo(false))
                .body("message", equalTo("Validation failed"))
                .body("payload", hasKey("size"));
    }

    @Test
    void usersAreFilteredByUsernameIgnoringCase() {
        AppUser admin = saveSearchFixture();

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .queryParam("username", "ALI")
        .when()
                .get("/user")
        .then()
                .statusCode(200)
                // Default order is by id: Alicia was saved before Alice
                .body("payload.content.name", contains("Alicia", "Alice"));
    }

    @Test
    void usersAreFilteredByEmail() {
        AppUser admin = saveSearchFixture();

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .queryParam("email", "@shop.com")
        .when()
                .get("/user")
        .then()
                .statusCode(200)
                .body("payload.totalElements", equalTo(2))
                .body("payload.content.email", contains("carol@shop.com", "alicia@shop.com"));
    }

    // The keyword matches either field; combined filters must all match
    @Test
    void keywordMatchesNameOrEmailAndFiltersCombine() {
        AppUser admin = saveSearchFixture();

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .queryParam("keyword", "carol")
        .when()
                .get("/user")
        .then()
                .statusCode(200)
                .body("payload.content.email", contains("carol@shop.com"));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .queryParam("username", "ali")
                .queryParam("email", "shop")
        .when()
                .get("/user")
        .then()
                .statusCode(200)
                .body("payload.content.name", contains("Alicia"));
    }

    // "_" and "%" are LIKE wildcards; in a filter they must only match themselves
    @Test
    void likeWildcardsInAFilterMatchLiterally() {
        AppUser admin = saveSearchFixture();

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .queryParam("username", "_")
        .when()
                .get("/user")
        .then()
                .statusCode(200)
                .body("payload.content", hasSize(0));
    }

    @Test
    void usersAreSortedByChosenField() {
        AppUser admin = saveSearchFixture();

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .queryParam("sortField", "EMAIL")
        .when()
                .get("/user")
        .then()
                .statusCode(200)
                .body("payload.content.email", contains(
                        "admin@email.com", "alice@email.com", "alicia@shop.com", "bob@email.com", "carol@shop.com"));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .queryParam("sortField", "NAME")
        .when()
                .get("/user")
        .then()
                .statusCode(200)
                .body("payload.content.name", contains("Admin", "Alice", "Alicia", "Bob", "Carol"));
    }

    @Test
    void unknownSortFieldIsBadRequest() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .queryParam("sortField", "PASSWORD")
        .when()
                .get("/user")
        .then()
                .statusCode(400)
                .body("success", equalTo(false));
    }

    @Test
    void filterLongerThan100CharactersIsBadRequest() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .queryParam("keyword", "a".repeat(101))
        .when()
                .get("/user")
        .then()
                .statusCode(400)
                .body("success", equalTo(false))
                .body("payload", hasKey("keyword"));
    }

    // Saved out of name and email order, so sorting is really exercised. Returns the admin.
    private AppUser saveSearchFixture() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));
        appUserRepository.saveAll(List.of(
                user("Carol", "carol@shop.com", UserRole.USER),
                user("Alicia", "alicia@shop.com", UserRole.MERCHANT),
                user("Bob", "bob@email.com", UserRole.USER),
                user("Alice", "alice@email.com", UserRole.USER)));
        return admin;
    }
}
