package com.waller.wallet_platform.user.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.waller.wallet_platform.security.JwtTokenProvider;
import com.waller.wallet_platform.outbox.repository.OutboxRepository;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;
import com.waller.wallet_platform.support.IntegrationTest;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
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

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    void adminGetsAllUsers() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));
        appUserRepository.saveAll(List.of(
                user("Alice", "alice@email.com", UserRole.USER),
                user("Bob", "bob@email.com", UserRole.USER)));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
        .when()
                .get("/api/users")
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
                .get("/api/users")
        .then()
                .statusCode(403);
    }

    @Test
    void anonymousIsUnauthorized() {
        given()
        .when()
                .get("/api/users")
        .then()
                .statusCode(401);
    }

    // Gone: users sign up only through /api/auth/register
    @Test
    void oldSignUpEndpointIsNotPublic() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("name", "newUser", "email", "new@email.com", "password", "passsworD!11"))
        .when()
                .post("/user/create")
        .then()
                .statusCode(401);

        assertThat(appUserRepository.count()).isZero();
    }

    @Test
    void adminGrantsMerchant_whichAppliesToTheUsersExistingToken() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));
        AppUser shop = appUserRepository.save(user("Shop", "shop@email.com", UserRole.USER));
        String shopToken = jwtTokenProvider.generateToken(shop);

        changeRole(admin, shop.getId(), "MERCHANT")
                .statusCode(200)
                .body("payload.role", equalTo("MERCHANT"));

        assertThat(appUserRepository.findById(shop.getId()).orElseThrow().getRole()).isEqualTo(UserRole.MERCHANT);
        assertThat(outboxRepository.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo("UserRoleChanged");
            assertThat(event.getAggregateId()).isEqualTo(shop.getId());
            assertThat(event.getPayload().get("role").asText()).isEqualTo("MERCHANT");
        });
        // Roles are read per request, so the token issued while still USER now passes hasRole('MERCHANT'): the
        // request gets as far as looking up the (missing) account instead of 403
        given()
                .auth().oauth2(shopToken)
                .contentType(ContentType.JSON)
                .body(Map.of("accountId", 999_999, "amount", 100, "currency", "USD",
                        "expiresAt", Instant.now().plus(Duration.ofHours(1)).toString()))
        .when()
                .post("/api/holds")
        .then()
                .statusCode(404);
    }

    @Test
    void grantingTheSameRoleAgain_changesNothing() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));
        AppUser shop = appUserRepository.save(user("Shop", "shop@email.com", UserRole.MERCHANT));

        changeRole(admin, shop.getId(), "MERCHANT").statusCode(200).body("payload.role", equalTo("MERCHANT"));

        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void merchantCanBeTurnedBackIntoUser() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));
        AppUser shop = appUserRepository.save(user("Shop", "shop@email.com", UserRole.MERCHANT));

        changeRole(admin, shop.getId(), "USER").statusCode(200).body("payload.role", equalTo("USER"));
    }

    // Each change records its own UserRoleChanged; the outbox dedup used to reject the second one for the same user
    @Test
    void merchantGrantedThroughTheApiCanBeTurnedBackIntoUser() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));
        AppUser shop = appUserRepository.save(user("Shop", "shop@email.com", UserRole.USER));

        changeRole(admin, shop.getId(), "MERCHANT").statusCode(200).body("payload.role", equalTo("MERCHANT"));
        changeRole(admin, shop.getId(), "USER").statusCode(200).body("payload.role", equalTo("USER"));

        assertThat(appUserRepository.findById(shop.getId()).orElseThrow().getRole()).isEqualTo(UserRole.USER);
        assertThat(outboxRepository.findAll())
                .extracting(event -> event.getPayload().get("role").asText())
                .containsExactly("MERCHANT", "USER");
    }

    @Test
    void adminRoleCanNotBeGranted() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));
        AppUser alice = appUserRepository.save(user("Alice", "alice@email.com", UserRole.USER));

        changeRole(admin, alice.getId(), "ADMIN").statusCode(400);

        assertThat(appUserRepository.findById(alice.getId()).orElseThrow().getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void adminCanNotBeDemoted() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));

        changeRole(admin, admin.getId(), "USER").statusCode(409);

        assertThat(appUserRepository.findById(admin.getId()).orElseThrow().getRole()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void onlyAdminsChangeRoles() {
        AppUser merchant = appUserRepository.save(user("Shop", "shop@email.com", UserRole.MERCHANT));
        AppUser alice = appUserRepository.save(user("Alice", "alice@email.com", UserRole.USER));

        changeRole(merchant, alice.getId(), "MERCHANT").statusCode(403);
        changeRole(alice, alice.getId(), "MERCHANT").statusCode(403);

        assertThat(appUserRepository.findById(alice.getId()).orElseThrow().getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void unknownUser_returns404() {
        AppUser admin = appUserRepository.save(user("Admin", "admin@email.com", UserRole.ADMIN));

        changeRole(admin, 999_999L, "MERCHANT").statusCode(404);
    }

    private ValidatableResponse changeRole(AppUser caller, Long userId, String role) {
        return given()
                .auth().oauth2(jwtTokenProvider.generateToken(caller))
                .contentType(ContentType.JSON)
                .body(Map.of("role", role))
        .when()
                .put("/api/users/{id}/role", userId)
        .then();
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
                .get("/api/users")
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
                .get("/api/users")
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
                .get("/api/users")
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
                .get("/api/users")
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
                .get("/api/users")
        .then()
                .statusCode(200)
                .body("payload.content.email", contains("carol@shop.com"));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .queryParam("username", "ali")
                .queryParam("email", "shop")
        .when()
                .get("/api/users")
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
                .get("/api/users")
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
                .get("/api/users")
        .then()
                .statusCode(200)
                .body("payload.content.email", contains(
                        "admin@email.com", "alice@email.com", "alicia@shop.com", "bob@email.com", "carol@shop.com"));

        given()
                .auth().oauth2(jwtTokenProvider.generateToken(admin))
                .queryParam("sortField", "NAME")
        .when()
                .get("/api/users")
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
                .get("/api/users")
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
                .get("/api/users")
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
