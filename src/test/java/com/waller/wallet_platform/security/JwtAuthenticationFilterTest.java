package com.waller.wallet_platform.security;

import static com.waller.wallet_platform.support.TestData.user;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.waller.wallet_platform.common.constants.AuthenticationConstants;
import com.waller.wallet_platform.support.IntegrationTest;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;

import io.jsonwebtoken.Jwts;

// Real tokens through the real filter chain, once for the whole API: GET /account stands in for any authenticated
// endpoint. Controller tests use @WithMockUser and never exercise JwtAuthenticationFilter.
class JwtAuthenticationFilterTest extends IntegrationTest {

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JwtKeys jwtKeys;

    @Autowired
    private AccessTokenDenylist accessTokenDenylist;

    private AppUser appUser;

    @BeforeEach
    void setUp() {
        appUser = appUserRepository.save(user("user", "user@email.com", UserRole.USER));
    }

    @Test
    void validToken_is200() {
        given()
                .auth().oauth2(jwtTokenProvider.generateToken(appUser))
        .when()
                .get("/api/accounts")
        .then()
                .statusCode(200)
                .body("success", equalTo(true));
    }

    @Test
    void expiredToken_is401() {
        // Signed with the application's own key, so only the expiry is wrong
        Instant issuedAt = Instant.now().minus(Duration.ofHours(2));
        String expired = Jwts.builder()
                .header().keyId(jwtKeys.getKeyId()).and()
                .id(UUID.randomUUID().toString())
                .subject(appUser.getEmail())
                .claim(AuthenticationConstants.USER_ID, appUser.getId())
                .claim(AuthenticationConstants.USERNAME, appUser.getName())
                .claim(AuthenticationConstants.ROLE, List.of(appUser.getRole().name()))
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(Duration.ofHours(1))))
                .signWith(jwtKeys.getPrivateKey(), Jwts.SIG.RS256)
                .compact();

        given()
                .auth().oauth2(expired)
        .when()
                .get("/api/accounts")
        .then()
                .statusCode(401)
                .body("success", equalTo(false));
    }

    @Test
    void denylistedToken_is401() {
        String token = jwtTokenProvider.generateToken(appUser);
        // What logout does with the access token (AuthServiceImpl)
        accessTokenDenylist.revoke(jwtTokenProvider.getTokenId(token), jwtTokenProvider.getExpiration(token));

        given()
                .auth().oauth2(token)
        .when()
                .get("/api/accounts")
        .then()
                .statusCode(401)
                .body("success", equalTo(false));
    }
}
