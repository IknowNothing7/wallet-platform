package com.waller.wallet_platform.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.networknt.schema.Schema;
import com.waller.wallet_platform.support.EventContracts;

import tools.jackson.databind.JsonNode;

// Each schema is a valid draft 2020-12 schema, is named after its title, and its examples conform to it. That the
// application's real payloads conform is checked after every integration test (IntegrationTest).
class EventSchemaExamplesTest {

    static Stream<Path> schemas() {
        return EventContracts.schemaFiles();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemas")
    void examplesConformToTheirSchema(Path file) {
        JsonNode schemaNode = EventContracts.read(file);
        assertThat(file.getFileName().toString()).startsWith(schemaNode.get("title").asString() + ".v");
        Schema schema = EventContracts.schema(schemaNode);

        assertThat(schemaNode.get("examples")).as("examples of %s", file).isNotEmpty();
        for (JsonNode example : schemaNode.get("examples")) {
            assertThat(schema.validate(example)).as("example %s of %s", example, file).isEmpty();
        }
    }
}
