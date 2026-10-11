package com.waller.notification_service.event.service;

import tools.jackson.databind.JsonNode;

/** Required-field access on an event payload; a missing field is a contract violation, not a retry case. */
public record JsonNodeFields(JsonNode node) {

    public long longValue(String field) {
        return require(field).asLong();
    }

    public String text(String field) {
        return require(field).asString();
    }

    private JsonNode require(String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Event payload has no field '" + field + "'");
        }
        return value;
    }
}
