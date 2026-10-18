package com.waller.wallet_platform.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The event schemas in contracts/events, loaded by event type ("TransferCompleted" ->
 * TransferCompleted.v1.schema.json). The single source of truth both for the producer's tests and for consumers.
 */
public final class EventContracts {

    // Gradle runs the tests from the project directory
    public static final Path EVENTS = Path.of("contracts", "events");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Map<String, Schema> SCHEMAS = new ConcurrentHashMap<>();

    private EventContracts() {
    }

    /** Violations of the event's v1 schema, empty when the payload conforms; fails if there is no schema at all. */
    public static List<String> violations(String eventType, JsonNode payload) {
        return SCHEMAS.computeIfAbsent(eventType, EventContracts::load).validate(payload).stream()
                .map(Error::toString)
                .toList();
    }

    public static Stream<Path> schemaFiles() {
        try {
            return Files.list(EVENTS).filter(p -> p.toString().endsWith(".schema.json")).sorted().toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode read(Path file) {
        try {
            return MAPPER.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Schema schema(JsonNode schemaNode) {
        return REGISTRY.getSchema(schemaNode);
    }

    private static Schema load(String eventType) {
        Path file = EVENTS.resolve(eventType + ".v1.schema.json");
        if (!Files.exists(file)) {
            throw new AssertionError("Event " + eventType + " is published but has no contract: add " + file);
        }
        return schema(read(file));
    }
}
