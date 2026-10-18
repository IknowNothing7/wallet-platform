package com.waller.notification_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class UserIdClaimValidatorTest {

    private final UserIdClaimValidator validator = new UserIdClaimValidator();

    @Test
    void positiveLong_isValid() {
        assertThat(validator.validate(jwt(7L)).hasErrors()).isFalse();
    }

    @Test
    void positiveInteger_isValid() {
        assertThat(validator.validate(jwt(7)).hasErrors()).isFalse();
    }

    @Test
    void missingClaim_isInvalidToken() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("alice")
                .issuedAt(Instant.now()).build();

        assertInvalid(validator.validate(jwt));
    }

    @Test
    void stringClaim_isInvalid() {
        assertInvalid(validator.validate(jwt("7")));
    }

    @Test
    void fractionalClaim_isInvalid() {
        assertInvalid(validator.validate(jwt(7.5)));
    }

    @Test
    void zeroOrNegative_isInvalid() {
        assertInvalid(validator.validate(jwt(0L)));
        assertInvalid(validator.validate(jwt(-1L)));
    }

    private static Jwt jwt(Object userId) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("alice")
                .claim(UserIdClaimValidator.USER_ID_CLAIM, userId).issuedAt(Instant.now()).build();
    }

    private static void assertInvalid(OAuth2TokenValidatorResult result) {
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement()
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_TOKEN));
    }
}
