package com.waller.wallet_platform.support;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.restassured.RestAssured;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for tests that run the whole application against Postgres.
 *
 * <p>The container is started once per JVM and every subclass shares one Spring context, so subclasses must not add
 * their own {@code @SpringBootTest} properties, {@code @MockitoBean}s or {@code @DirtiesContext}: any of those gives
 * the class a context of its own. There is no Kafka and no background job runs; tests call the services directly.
 *
 * <p>Before each test the database is reset to its freshly migrated state, so tests can't see each other's rows.
 *
 * <p>After each test, every event the application wrote to the outbox is validated against its schema in
 * contracts/events: the contract tests. A test that inserts made-up outbox rows overrides
 * {@link #checksEventContracts()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Without a broker the publisher would hold outbox row locks while it waits; one poll at startup is harmless
        "outbox.publisher.poll-interval=PT1H",
        "hold.expiry.poll-interval=PT1H",
        "deposit.reconciliation.poll-interval=PT1H" })
public abstract class IntegrationTest {

    // Started here rather than by @Testcontainers, which would stop it after each class; Ryuk removes it at JVM exit
    @ServiceConnection
    static final PostgreSQLContainer postgresql = new PostgreSQLContainer("postgres:17-alpine");

    static {
        postgresql.start();
    }

    private static List<String> tables;

    @LocalServerPort
    private Integer port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabaseAndRestAssured() {
        RestAssured.port = port;
        resetDatabase();
    }

    // shedlock is kept: ShedLock remembers which lock rows exist and only UPDATEs them, so after a TRUNCATE a job
    // called by a test would find its row gone and skip the run as "locked"
    @AfterEach
    void outboxEventsMatchTheirContracts() {
        if (!checksEventContracts()) {
            return;
        }
        JsonMapper mapper = JsonMapper.builder().build();
        jdbcTemplate.query("SELECT id, event_type, payload::text AS payload FROM outbox_event ORDER BY id", row -> {
            String eventType = row.getString("event_type");
            assertThat(EventContracts.violations(eventType, mapper.readTree(row.getString("payload"))))
                    .as("outbox event %d (%s) against contracts/events/%s.v1.schema.json",
                            row.getLong("id"), eventType, eventType)
                    .isEmpty();
        });
    }

    protected boolean checksEventContracts() {
        return true;
    }

    private void resetDatabase() {
        if (tables == null) {
            tables = jdbcTemplate.queryForList("""
                    SELECT quote_ident(tablename) FROM pg_tables
                    WHERE schemaname = 'public' AND tablename NOT IN ('flyway_schema_history', 'shedlock')""", String.class);
        }
        jdbcTemplate.execute("TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        // Seed rows from the migrations; keep in sync with V5__transfer_limit.sql
        jdbcTemplate.update("""
                INSERT INTO transfer_limit (currency, per_transfer_max, daily_max) VALUES
                    ('USD', 1000000, 5000000),
                    ('EUR', 1000000, 5000000)""");
    }
}
