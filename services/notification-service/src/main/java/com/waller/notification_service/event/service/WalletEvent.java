package com.waller.notification_service.event.service;

import tools.jackson.databind.JsonNode;

/** One record from a wallet.* topic: the outbox headers plus the parsed payload. */
public record WalletEvent(
        long eventId,
        String eventType,
        String aggregateType,
        long aggregateId,
        JsonNode payload
) {
}
