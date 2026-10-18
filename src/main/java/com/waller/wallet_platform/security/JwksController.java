package com.waller.wallet_platform.security;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

// Public keys only, read by notification-service to verify access tokens (permitted in SecurityConfig)
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final JwtKeys jwtKeys;

    @GetMapping(value = "/.well-known/jwks.json", produces = "application/jwk-set+json")
    public Map<String, List<Map<String, String>>> jwks() {
        return Map.of("keys", List.of(jwtKeys.publicJwk()));
    }
}
