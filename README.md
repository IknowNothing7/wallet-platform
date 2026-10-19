# wallet-platform

A digital wallet with deposits, transfers and holds, built as three Spring Boot services:

- **wallet-service** (`src/`, port 8080) holds accounts, the ledger, deposits, transfers and holds. It publishes events
  through an outbox to Kafka.
- **bank-sim** (`services/bank-sim`, port 8081) is a fake bank. It processes deposits and reports results back to
  wallet-service through a signed webhook.
- **notification-service** (`services/notification-service`, port 8082) consumes the events and stores notifications
  for users.

## Run

Start everything (infrastructure and the three services):

```bash
docker compose --profile app up --build
```

Start only the infrastructure (Postgres, Kafka, Prometheus, Grafana), for running a service from the IDE or with
`./gradlew bootRun`:

```bash
docker compose up
```

Grafana is on http://localhost:3001, Prometheus on http://localhost:9090 and Jaeger (traces) on http://localhost:16686.

### Tracing

All three services send OpenTelemetry traces over OTLP to Jaeger (`OTLP_TRACES_ENDPOINT`, default
`http://localhost:4318/v1/traces`). A deposit is one trace end to end: the HTTP request, the bank call, bank-sim's
webhook, the outbox publish and notification-service's consumer. Log lines carry the trace and span ids. Every request
is sampled; set `TRACING_SAMPLING_PROBABILITY` (0.0 to 1.0) to sample less. Grafana also has Jaeger as a data source.

### Admins and merchants

Everyone who signs up through `POST /api/auth/register` is a `USER`. The first `ADMIN` is created on startup from
environment variables, if no user has that email yet:

```bash
ADMIN_EMAIL=admin@example.com ADMIN_PASSWORD='Change-me-1!' docker compose --profile app up --build
```

That admin makes a user a merchant (or back) with `PUT /api/users/{id}/role` and the body `{"role": "MERCHANT"}`. The
`ADMIN` role can't be granted or taken away over the API.

## Test

Docker must be running, because the integration tests use Testcontainers.

```bash
./gradlew test
```

The ledger also has a mutation testing gate ([PIT](https://pitest.org)): its unit tests must catch deliberate changes
to the code that moves money. It fails below a 95% mutation score; the report is in `build/reports/pitest/`.

```bash
./gradlew pitest
```

## More

- Design decisions (locking, bank retries, events, schema): [docs/decisions.md](docs/decisions.md)
- Event schemas: [contracts/](contracts/README.md)
