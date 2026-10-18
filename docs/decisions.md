# Design decisions

Short records of choices that are not obvious from the code, and why they were made. Each one states what would make
it worth revisiting.

## Money moves synchronously, under row locks

A transfer is booked in the request that creates it (`TransferProcessingServiceImpl.book`) and the response is already
`COMPLETED` or `FAILED`. Both accounts are rows in one Postgres database, so there is nothing to wait for that would
justify eventual consistency, polling or retries.

There is one locking strategy: every account an entry touches is row-locked with `SELECT ... FOR UPDATE` before its
balance is read, **user accounts first, then system accounts, each by id**. `LedgerServiceImpl.post` takes all of
them in one ordered query, so every caller (transfer, reversal, deposit, hold capture) inherits it. Callers that must
check something before posting (a transfer's available balance, a capture's destination) lock their user accounts
the same way first, and the ledger's lock is then a no-op. The fixed order is what makes it deadlock-free. Locking
system accounts last means the hot `FEES` row is held only for the end of each transaction.
`TransferConcurrencyTest` races 100 transfers to show it. `@Version` on `Account` only catches a write that bypasses
this path.

Revisit if the accounts of one transfer can live in different databases. That is the point where a saga, and the
asynchrony it brings, starts paying for itself.

## The ledger is append-only, enforced by the schema

There is no HTTP endpoint that writes to the ledger. `V11` adds `CHECK (type = 'SYSTEM' OR balance >= 0)`, a unique
`(reference_type, reference_id, entry_type)` (one booking per business object and entry type), and a trigger that
rejects `UPDATE`/`DELETE` on `journal_entry` and `posting`. The trigger is there because privileges don't bind the
superuser that compose and Testcontainers connect as. Corrections are new entries (`REVERSAL`).

## Talking to the bank

- **Submission is asynchronous.** `POST /api/deposits` stores the deposit as `PENDING` and returns. The bank call
  runs after commit on a small bounded pool (`deposit.submission.*`), so a slow bank (5 s timeout, longer under
  bank-sim's latency scenario) never holds a request thread. The result arrives by webhook.
- **No retries inside the request, and no circuit breaker.** `BankClient` has a timeout and nothing else, on purpose.
  Retrying is `DepositReconciliationJob`'s job: a deposit still `PENDING` after `stale-after` is resubmitted if it
  never reached the bank, or looked up at the bank if it did. The resubmission reuses the deposit's idempotency key,
  and bank-sim answers a repeated key with the original payment, so retrying can never create a second top-up. A
  full submission queue, a crash between commit and the bank call, and a bank outage all end the same way: the
  deposit waits for the next reconciliation run. That gives one recovery path instead of two.
- Revisit with a real PSP and real volume: a circuit breaker (resilience4j) would stop each reconciliation run from
  spending its whole batch on timeouts while the bank is down.

## Events

Events are published only where a consumer has a reason to care about a business fact: the life cycle of a
deposit, transfer or hold, plus account and user creation. They are not published for internal bookkeeping:

- No ledger events (`JournalEntryCreated`/`PostingsCreated` were removed). The journal is an internal record, and each
  booking is already announced by its business event in the same transaction.
- No `TransferCreated`: a transfer has no observable state before it is booked.
- No PII on the bus. User events carry id, role and timestamps. A consumer that needs to reach a user asks
  wallet-service by id.

Every event the application writes during the integration tests is validated against `contracts/events` after each
test (`IntegrationTest`), and every schema's examples are validated as well (`EventSchemaExamplesTest`). An event
without a schema fails the build.

## Schema choices

| Question | Decision |
|---|---|
| Accounts per user and currency | **One** (`V14`, `uq_account_user_currency`). "My USD account" is unambiguous, and a second one is refused with 409. Revisit if users need separate pockets. |
| Currencies | Accounts may be opened in any ISO code, but **transfers fail closed**: a currency without a `transfer_limit` row is not enabled for transfers. `V5` seeds USD and EUR. Enabling another currency is a migration that adds its limit row. |
| `deposit.gateway` with `'stripe'`/`'adyen'` in the V1 comment | Kept. It names the payment provider adapter (`bank-sim` today) and is part of `uq_deposit_gateway_ref`, so a second provider can't collide on references. The comment in V1 is stale; applied migrations aren't edited. |
| `BEGIN`/`COMMIT` inside `V1` | Left as is. Flyway already runs each Postgres migration in a transaction, so the explicit pair is redundant but harmless, and editing an applied migration would change its checksum. New migrations don't add one. |
| `AccountRepository.findByUser(Long)` | Removed: unused, and not a valid derived query for a `ManyToOne`. |

## JSON

Application code uses Jackson 3 only (`tools.jackson`), including the `jsonb` columns: Hibernate is pointed at
`Jackson3JsonFormatMapper`. Jackson 2 stays on the classpath only as a transitive dependency of swagger-core
(springdoc) and jjwt-jackson. Revisit when both have Jackson 3 releases.

## HTTP API

Everything a client calls lives under `/api` with plural nouns and no verbs: `POST /api/transfers`,
`GET /api/accounts/{id}/transfers`, `POST /api/holds/{id}/capture`. Actions that are not plain creates stay
sub-resources (`/capture`, `/release`, `/reverse`). A single prefix lets an ingress route the service with one rule.
Outside `/api` are only machine endpoints: the bank webhook (`/webhooks/bank`), the JWKS (`/.well-known/jwks.json`),
and actuator.
