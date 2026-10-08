package com.waller.wallet_platform.service;

import com.waller.wallet_platform.model.entites.AppUser;

public interface RefreshTokenService {

    /** Creates a new refresh token for the user and returns the raw value (only its hash is stored). */
    String issue(AppUser user);

    /** Validates the raw token, revokes it and issues a replacement for the same user. */
    Rotation rotate(String rawToken);

    void revoke(String rawToken);

    void revokeAll(Long userId);

    record Rotation(AppUser user, String refreshToken) {
    }

}
