package com.waller.notification_service.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Every endpoint scopes its data by the "user_id" claim, so a token without a positive whole-number user id is
 * rejected when it is decoded (401) instead of failing later in a controller (500).
 */
public class UserIdClaimValidator implements OAuth2TokenValidator<Jwt> {

    // Claim name used by wallet-service's JwtTokenProvider
    public static final String USER_ID_CLAIM = "user_id";

    private static final OAuth2Error INVALID_USER_ID = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
            "The " + USER_ID_CLAIM + " claim must be a positive integer", null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object value = jwt.getClaims().get(USER_ID_CLAIM);
        // Nimbus reads JSON integers as Long; a string "7" or 7.5 is not a user id
        if ((value instanceof Long || value instanceof Integer) && ((Number) value).longValue() > 0) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(INVALID_USER_ID);
    }
}
