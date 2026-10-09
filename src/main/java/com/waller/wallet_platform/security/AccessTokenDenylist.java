package com.waller.wallet_platform.security;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Access tokens revoked by logout, keyed by their jti until they would have expired anyway.
 * In-memory: entries are lost on restart and not shared between instances, so keep jwt.lifetime short.
 */
@Component
public class AccessTokenDenylist {

    private final ConcurrentMap<String, Instant> revoked = new ConcurrentHashMap<>();

    public void revoke(String tokenId, Instant expiresAt) {
        if (tokenId != null && expiresAt != null && expiresAt.isAfter(Instant.now())) {
            revoked.put(tokenId, expiresAt);
        }
    }

    public boolean isRevoked(String tokenId) {
        return tokenId != null && revoked.containsKey(tokenId);
    }

    @Scheduled(fixedDelayString = "${auth.rate-limit.cleanup-interval:PT5M}")
    public void evictExpired() {
        Instant now = Instant.now();
        revoked.values().removeIf(expiresAt -> !expiresAt.isAfter(now));
    }

}
