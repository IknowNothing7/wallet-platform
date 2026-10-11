# Contracts

Payloads that cross a service boundary, as JSON Schema (draft 2020-12). One file per message type and version:
`<MessageType>.v<N>.schema.json`. Each schema carries an `examples` entry that must validate against it.

```
contracts/
├── events/      # Kafka events published by wallet-service through the outbox
└── webhooks/    # HTTP callbacks between services
```

## Events

wallet-service writes events to its outbox table; `OutboxPublisher` sends them to Kafka.

| Part | Value |
|---|---|
| Topic | `wallet.<aggregate_type>` (`x-topic` in each schema) |
| Key | aggregate id as a decimal string, so all events of one aggregate land in one partition, in order |
| Value | JSON payload described by the schema |
| Headers | `event-id` (outbox row id, unique: deduplicate by it), `event-type` (= schema title), `aggregate-type`, `aggregate-id`, `created-at` |

| Event | Topic | Payload |
|---|---|---|
| [AccountCreated](events/AccountCreated.v1.schema.json) | `wallet.account` | account |
| [DepositCreated](events/DepositCreated.v1.schema.json) | `wallet.deposit` | deposit |
| [TransferCreated](events/TransferCreated.v1.schema.json) | `wallet.transfer` | transfer |
| [HoldCreated](events/HoldCreated.v1.schema.json), [HoldCaptured](events/HoldCaptured.v1.schema.json), [HoldReleased](events/HoldReleased.v1.schema.json), [HoldExpired](events/HoldExpired.v1.schema.json) | `wallet.hold` | hold |
| [UserCreated](events/UserCreated.v1.schema.json) | `wallet.user` | user (contains email: PII) |
| [JournalEntryCreated](events/JournalEntryCreated.v1.schema.json) | `wallet.journal_entry` | journal entry, without postings |
| [PostingsCreated](events/PostingsCreated.v1.schema.json) | `wallet.journal_entry` | array of postings |

Conventions in every payload:

- Amounts are integers in minor units (cents); currency is an ISO 4217 code.
- Timestamps are ISO 8601 UTC strings.
- Every listed field is present; optional values are sent as `null`, not left out.
- Ordering is guaranteed only within one aggregate. Events on different topics can arrive in any order
  (e.g. `DepositCreated` before the `AccountCreated` of its account), so consumers must cope with that.
- Delivery is at least once. Consumers deduplicate by the `event-id` header.

## Webhooks

| Contract | From → to | Notes |
|---|---|---|
| [BankPaymentResult](webhooks/BankPaymentResult.v1.schema.json) | bank-sim → wallet-service | HMAC-signed (`X-Bank-Signature` over `"<X-Bank-Timestamp>.<raw body>"`), at least once, deduplicate by `reference` |

## Changing a contract

Schemas allow additional properties, so consumers must ignore fields they do not know.

- **Compatible change** (new optional field, new event type): edit the current version in place, update its example,
  and ship the producer and the schema in the same PR.
- **Breaking change** (remove or rename a field, change a type or meaning, make a field required, add an enum value
  that consumers would treat as an error): add `<MessageType>.v<N+1>.schema.json`, keep the old file, and publish
  both versions until every consumer has moved. Event versions will travel in an `event-version` header; it does not
  exist yet, so the absence of the header means v1.
