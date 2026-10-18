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

Grafana is on http://localhost:3001 and Prometheus on http://localhost:9090.

### Admins and merchants

Everyone who signs up through `POST /api/auth/register` is a `USER`. The first `ADMIN` is created on startup from
environment variables, if no user has that email yet:

```bash
ADMIN_EMAIL=admin@example.com ADMIN_PASSWORD='Change-me-1!' docker compose --profile app up --build
```

That admin makes a user a merchant (or back) with `PUT /user/{id}/role` and the body `{"role": "MERCHANT"}`. The
`ADMIN` role can't be granted or taken away over the API.

## Test

Docker must be running, because the integration tests use Testcontainers.

```bash
./gradlew test
```

## More

- Event schemas: [contracts/](contracts/README.md)
