package com.waller.wallet_platform.user.dto;

import java.time.Instant;
import com.waller.wallet_platform.user.entity.UserRole;

// Payload of the wallet.user events. No name or email: the bus is not the place for PII, and a consumer that needs
// to reach the user asks wallet-service for it by id.
public record UserEvent(Long id, Instant createdAt, Instant updatedAt, UserRole role) {
}
