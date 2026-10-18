package com.waller.wallet_platform.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.waller.wallet_platform.common.constants.AuthenticationConstants;
import com.waller.wallet_platform.support.IntegrationTest;
import com.waller.wallet_platform.user.entity.AppUser;
import com.waller.wallet_platform.user.entity.UserRole;
import com.waller.wallet_platform.user.repository.AppUserRepository;

import static com.waller.wallet_platform.support.TestData.user;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;

// What notification-service relies on: an access token verifies with nothing but the public JWKS
class JwksControllerTest extends IntegrationTest {

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private AppUserRepository appUserRepository;

    @Test
    void accessTokenVerifiesAgainstThePublishedKey() throws Exception {
        AppUser alice = appUserRepository.save(user("Alice", "alice@email.com", UserRole.USER));
        String token = jwtTokenProvider.generateToken(alice);

        List<Map<String, String>> keys = given()
        .when()
                .get("/.well-known/jwks.json")
        .then()
                .statusCode(200)
                .extract().jsonPath().getList("keys");

        assertThat(keys).singleElement().satisfies(jwk -> {
            assertThat(jwk).containsEntry("kty", "RSA").containsEntry("alg", "RS256").containsEntry("use", "sig");
            assertThat(jwk).doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
        });
        Map<String, String> jwk = keys.get(0);
        Jws<Claims> verified = Jwts.parser().verifyWith(publicKey(jwk)).build().parseSignedClaims(token);

        assertThat(verified.getHeader().getKeyId()).isEqualTo(jwk.get("kid"));
        assertThat(verified.getHeader().getAlgorithm()).isEqualTo("RS256");
        assertThat(verified.getPayload().get(AuthenticationConstants.USER_ID, Number.class).longValue())
                .isEqualTo(alice.getId());
    }

    private static PublicKey publicKey(Map<String, String> jwk) throws Exception {
        Base64.Decoder decoder = Base64.getUrlDecoder();
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, decoder.decode(jwk.get("n"))), new BigInteger(1, decoder.decode(jwk.get("e")))));
    }
}
