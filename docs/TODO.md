# TODOs

All `TODO` comments in the codebase, collected on 2026-10-15 (78 total) and last checked against the code on
2026-10-18 (49 left). Paths are relative to the repo root; `wallet-service` code lives under
`src/main/java/com/waller/wallet_platform/` and its tests under `src/test/java/com/waller/wallet_platform/`. Line
numbers are as of 2026-10-18; done items keep the line they had when they were done.

## Road to done

"Done" here means: the three money flows (deposit, transfer, hold) work end to end, the whole system starts with one
command, and the README explains it. The sections below this one list every TODO; this one picks the order.

### Must do

1. [x] **Deposits never reach the bank.** Done: V4 makes `gateway_reference` nullable, `DepositSubmissionListener`
   calls `submitToBank` after `createDeposit` commits, `expiresAt` is set from `deposit.pending-ttl`, and the
   reconciliation job resubmits a deposit that never got a bank reference.
2. [x] **One-command startup.** Done: `docker compose --profile app up --build`. One `Dockerfile` (`--build-arg
   SERVICE=wallet-service|bank-sim|notification-service`) builds all three jars in a shared stage. The services sit in
   the `app` profile so plain `docker compose up` (and Boot's Docker Compose support for `bootRun`) still starts only
   the infrastructure. Kafka has a second listener, `kafka:19092`, for containers. Smoke-tested: register, account,
   deposit, bank-sim webhook, outbox, Kafka, notification. notification-service now re-matches its topic pattern every
   10 s (`metadata.max.age.ms`); with the 5-minute default a fresh stack showed no notifications at first.
   - [ ] notification-service validates JWTs against `WALLET_JWKS_URI` (`/.well-known/jwks.json`), but wallet-service
     signs HS512 with `jwt.secret` and serves no JWKS, so its notification API rejects every wallet token. Either
     move wallet-service to an asymmetric key with a JWKS endpoint, or give notification-service the shared secret.
3. [x] **Test notification-service's dead-letter publishing.** Done: `WalletEventDlqTest` (Kafka + Postgres
   Testcontainers) covers a record without headers and malformed JSON (straight to `<topic>.DLT`, handler never
   called) and `UnknownAccountException` (retried, then dead-lettered with the original headers). It found two bugs,
   both fixed: Spring Kafka 4 defaults to `<topic>-dlt`, so the destination is now set explicitly, and the
   `wallet\..*` pattern also matched the DLT, so the service consumed its own dead letters (now `wallet\.[a-z_]+`).
4. [ ] **README.** It is one line, and `HELP.md` is the Spring Initializr default (delete it). Cover what the system does,
   the architecture (wallet-service → outbox → Kafka → notification-service; bank-sim → signed webhook →
   wallet-service), how to run it, and how to use the bank-sim scenarios (`/control`) to demonstrate failures.

### Should do

- [x] Idempotency-Key on `POST /transfer/create`.
- [x] Bank error handling in `BankClient`: retry, 4xx vs 5xx, 404 (Bank integration). Done, see below.
- [x] Deposits into a non-ACTIVE account are rejected (`DepositServiceImpl.java:55`). Still needs a test (Tests,
  Deposits `DepositServiceTest.java:188`).
- [x] Admin endpoint for transfer reversal, with both accounts locked in id order.
- [x] Event schemas for `DepositSucceeded|DepositFailed|DepositExpired` and
  `TransferCompleted|TransferFailed|TransferReversed`.
- [ ] Notification templates for deposit results (Other services, `NotificationTemplates.java:27`).
- [x] One shared Postgres container for the `@SpringBootTest` classes (Tests, Shared setup). Done: `support/IntegrationTest`.

### Can wait (list as known limitations in the README)

Multi-instance job claiming (ShedLock / SKIP LOCKED), metrics and alerts, fraud/velocity checks, partial reversals,
storing the fee on the transfer (reversals recompute it from the current rate), cancelling expired payments at the
bank, webhook secret rotation, push notifications, rate limiting the webhook endpoint, and the test cleanup items below.

## wallet-service — main code

### Deposits

- [ ] `deposit/repository/DepositRepository.java:41` — `idx_deposit_pending` is on `(status, expires_at)`; add one on
  `(status, updated_at)` if this gets slow.
- [ ] `deposit/job/DepositReconciliationJob.java:50` — With several wallet-service instances every one runs this; add
  ShedLock or SKIP LOCKED claiming.
- [ ] `deposit/job/DepositReconciliationJob.java:61` — Metrics: count of stale PENDING deposits and oldest age, for an
  alert.
- [x] `deposit/job/DepositReconciliationJob.java:69` — Done: a deposit without a bank reference is resubmitted with the
  same idempotency key.
- [x] `deposit/service/DepositProcessingServiceImpl.java:43` — Done: `DepositSucceeded`, `DepositFailed` and
  `DepositExpired` schemas, and the README row. `DepositCreated` now allows a null `gatewayReference` (it is set
  after `submitToBank`).
- [x] `deposit/service/DepositProcessingServiceImpl.java:65` — Done: `gateway_reference` is nullable (V4) and
  `DepositSubmissionListener` calls `submitToBank` after `createDeposit` commits.
- [x] `deposit/service/DepositProcessingServiceImpl.java:70` — Done: `expiresAt` is set on creation from
  `deposit.pending-ttl` (default `PT30M`).
- [x] `deposit/service/DepositProcessingServiceImpl.java:71` — Done: `DepositServiceImpl.createDeposit` rejects
  non-ACTIVE accounts.
- [x] `deposit/service/DepositProcessingServiceImpl.java:96` — Done: `DepositAlerts` is raised for a webhook about a
  missing deposit.
- [x] `deposit/service/DepositProcessingServiceImpl.java:102` — Done: an amount/currency/reference mismatch raises
  `RESULT_MISMATCH` and leaves the deposit for manual review.
- [x] `deposit/service/DepositProcessingServiceImpl.java:111` — Done: a late result for a settled deposit raises
  `STATUS_CONFLICT`; status and ledger are left for a manual credit or refund.
- [x] `deposit/service/DepositProcessingServiceImpl.java:136` — Done: `expire()` asks the bank (`getPayment`) first.
- [ ] `deposit/service/DepositProcessingServiceImpl.java:141` — Cancel the payment at the bank once bank-sim supports
  it; a late success now raises `STATUS_CONFLICT`.
- [x] `deposit/service/DepositProcessingServiceImpl.java:153` — Balances only change once `PostingServiceImpl` applies
  postings. Done: postings now move balances.
- [x] `deposit/service/DepositProcessingServiceImpl.java:154` — Done: 2% fee posted to `SystemAccountKind.FEES`,
  credited amount = amount - fee.
- [x] `deposit/service/DepositProcessingServiceImpl.java:169` — Done: once set, `gateway_reference` must equal
  `result.reference()`.

### Ledger

- [x] `ledger/service/PostingServiceImpl.java:67` — **Apply postings to account balances.** Done: a CREDIT adds the
  amount, a DEBIT subtracts it. There is no `balance >= 0` constraint, so the bank clearing account going negative on
  deposits is expected.

### Transfers

- [ ] `transfer/job/TransferProcessingJob.java:42` — With several wallet-service instances every one runs this; add
  ShedLock or SKIP LOCKED claiming.
- [ ] `transfer/job/TransferProcessingJob.java:43` — Set pending-for to 0 if processing becomes asynchronous.
- [ ] `transfer/job/TransferProcessingJob.java:51` — Count attempts and fail the transfer after N, so one broken row
  isn't retried forever.
- [ ] `transfer/job/TransferProcessingJob.java:55` — Metrics: count of PENDING transfers and oldest age, for an alert.
- [x] `transfer/service/TransferProcessingServiceImpl.java:38` — Done: `TransferCompleted`, `TransferFailed` and
  `TransferReversed` schemas, and the README row.
- [x] `transfer/service/TransferProcessingServiceImpl.java:52` — Done: `TransferProcessingJob` processes PENDING
  transfers every `transfer.processing.poll-interval` (asynchronous; the client polls `GET /transfer/{id}`).
- [x] `transfer/service/TransferProcessingServiceImpl.java:55` — Done: `POST /transfer/create` takes an optional
  `Idempotency-Key` header (`common/idempotency/IdempotencyService`).
- [x] `transfer/service/TransferProcessingServiceImpl.java:86` — Balances only change once `PostingServiceImpl` applies
  postings. Done.
- [x] `transfer/service/TransferProcessingServiceImpl.java:88` — Done: 2% fee taken from what the recipient gets and
  credited to `SystemAccountKind.FEES`; a fee that rounds to 0 gets no posting.
- [x] `transfer/service/TransferProcessingServiceImpl.java:96` — Done: `POST /transfer/{id}/reverse`, admin only.
- [x] `transfer/service/TransferProcessingServiceImpl.java:109` — Done: both accounts are locked in id order. A
  recipient who no longer has the money (balance minus active holds) gets 409 and the transfer stays COMPLETED. The
  reversal refunds the fee too.
- [ ] `transfer/service/TransferProcessingServiceImpl.java:111` — Partial reversals (amount < transfer amount) would
  need a `reversed_amount` column. Comment removed; not done (Can wait).
- [x] `transfer/service/TransferProcessingServiceImpl.java:146` — Done: per-transfer and daily limits (`transfer_limit`,
  V5; an account row overrides the currency default). Fraud/velocity checks are not planned for now (Can wait).

### Bank integration

- [ ] `bank/webhook/BankWebhookVerifier.java:60` — Accept a second (previous) secret during rotation, so bank-sim and
  wallet-service can switch separately.
- [ ] `bank/webhook/BankWebhookController.java:45` — Store failed webhooks somewhere (dead-letter table) and alert
  instead of only logging.
- [ ] `bank/webhook/BankWebhookController.java:50` — Rate limit / size limit this endpoint like `/api/auth/**`; it is
  public.
- [x] `bank/client/BankClient.java` — Retries: decided, left to `DepositReconciliationJob` (resubmits with the same
  key). `BankClient` does not retry.
- [x] `bank/client/BankClient.java` — Done: 4xx throws `BankRejectedException` and `submitToBank` marks the deposit
  FAILED (reason `Rejected by bank (<status>): <body>`, cut to 255) with a `DepositFailed` event; 5xx and timeouts
  propagate and it stays PENDING. Tests: `BankClientTest`, `DepositProcessingServiceTest`.
- [x] `bank/client/BankClient.java` — 404 from `getPayment`: already handled, `findBankPayment` in
  `DepositProcessingServiceImpl` treats it as "no payment".

## wallet-service — tests

### Shared setup

- [x] `support/IntegrationTest.java` — Done: the `@SpringBootTest` classes extend one base class with a Postgres
  container started once per JVM and one shared context (no Kafka, background jobs parked). Before each test it
  truncates every table and re-inserts the V5 transfer-limit defaults; the suite went from ~2 min to ~15 s. Subclasses
  must not add properties, `@MockitoBean`s or `@DirtiesContext`, or they get a context of their own.
- [ ] `support/TestData.java:10` — Deposit and outbox tests build entities by hand in `setUp()`; add factories here
  (hold, outboxEvent) as they are needed.

### Outbox

- [ ] `outbox/messaging/OutboxPublisherTest.java:37` — Either restore `@SuppressWarnings("unchecked")`
  (`mock(KafkaTemplate.class)` is an unchecked conversion) or delete the commented-out line.
- [ ] `outbox/messaging/OutboxPublisherTest.java:148` — Missing publisher cases:
  - After the pause runs out, publishing resumes, and a successful batch resets the backoff (use
    `initialBackoff = Duration.ZERO` so nothing has to sleep).
  - The backoff stops growing at `maxBackoff`.
  - An exception that only wraps a `RetriableException` is still treated as "broker unavailable" (the `classify()`
    cause chain).
  - `lastError` is cut to 2000 characters.
- [ ] `outbox/messaging/OutboxPublisherTest.java:156` — Nothing covers `OutboxEventServiceImpl`,
  `OutboxEventController` or the native queries in `OutboxRepository`:
  - Unit tests: `retryEvent` 404 vs 409; `getEvents` with and without a status; `record()` rejects calls outside a
    transaction (`Propagation.MANDATORY`).
  - Testcontainers: `lockPendingBatch` returns only PENDING rows, oldest first, and skips rows another transaction has
    locked; `resetFailedToPending` resets only FAILED rows.
  - Controller: non-admins get 403 on `/outbox`.

### Accounts

- [ ] `account/controller/AccountControllerTest.java:55` — Users are never deleted. The next test that saves
  `admin@email.com` will fail on the unique email, so also call `appUserRepository.deleteAll()` here, after accounts.
- [ ] `account/controller/AccountControllerTest.java:61` — Rename: this user is called "Admin" but has role USER, and the
  test checks the owner's own accounts.
- [ ] `account/controller/AccountControllerTest.java:63` — Also save an account owned by another user and assert it is
  NOT returned; right now nothing proves that `/account` returns only the caller's accounts.
- [ ] `account/controller/AccountControllerTest.java:91` — Missing endpoint tests:
  - `GET /account/{id}`: owner gets 200; another user gets 404 (not 403); an admin can read any user account.
  - `GET /account/{id}` for a system account gives 404, even for an admin.
  - Account creation: happy path, plus a validation error such as a bad currency.
- [ ] `account/service/AccountServiceTest.java:40` — `AccountServiceImpl` only takes accountRepository, userRepository,
  accountMapper, accountAccessChecker and outboxEventService. Remove the other mocks (userMapper, passwordEncoder,
  jwtTokenProvider, refreshTokenService, accessTokenDenylist); they look like dependencies but are never used.
- [ ] `account/service/AccountServiceTest.java:101` — Remove the log line and `@Slf4j`. Also, the `assertEquals` calls
  compare the mapper's own stub to itself; `assertThat(result).isEqualTo(accountDto)` says the same thing in one line.
- [ ] `account/service/AccountServiceTest.java:129` — Missing cases:
  - `getAccount` when the account exists but `accessChecker.canAccess` is false gives the same "Account not found".
  - `createAnAccount`: happy path (records the outbox event), and an unknown user id.
  - `getMyAccounts`.

### Deposits

- [ ] `deposit/controller/DepositControllerTest.java:39` — The other container tests use
  `static PostgreSQLContainer postgresql`; pick one name and drop `private`.
- [ ] `deposit/controller/DepositControllerTest.java:67` — Same naming problem as `AccountControllerTest`: "Admin" with
  role USER.
- [ ] `deposit/controller/DepositControllerTest.java:86` — `GET /deposit` isn't mapped; this passes only because
  security rejects every path first. Point it at a real endpoint, e.g. `/deposit/account/{accountId}`.
- [ ] `deposit/controller/DepositControllerTest.java:97` — Missing endpoint tests:
  - `POST /deposit/create`: 201, the deposit row is saved, and a DepositCreated outbox event is written in the same
    transaction (see `TransferControllerTest`).
  - Another user reading `/deposit/account/{id}` or `/deposit/{id}` gets 404.
  - The publisher polls a missing Kafka broker in the background; set `outbox.publisher.poll-interval=PT1H` like
    `TransferControllerTest` before adding tests that write outbox events.
- [ ] `deposit/service/DepositServiceTest.java:48` — `PER_METHOD` is already JUnit's default; remove `@TestInstance`
  here, in `AccountServiceTest` and `AppUserServiceTest`. Also remove the unused imports (`DepositServiceImpl` is in
  this package).
- [ ] `deposit/service/DepositServiceTest.java:130` — Argument order is reversed (expected value goes first), so a
  failure message would be misleading.
- [ ] `deposit/service/DepositServiceTest.java:134` — This should be `account.getId()`. It passes only because the user
  and account ids are both 1.
- [ ] `deposit/service/DepositServiceTest.java:150` — Also `verifyNoInteractions(repository, outboxEventService)`.
  Without it, a deposit saved before the account check would still pass.
- [ ] `deposit/service/DepositServiceTest.java:186` — Missing cases: currency mismatch (400, nothing saved), and
  `getDeposit` for your own deposit, someone else's, and a missing one. Also reformat the last two tests; their
  indentation is off.
- [ ] `deposit/service/DepositServiceTest.java:188` — The comment is stale: `createDeposit` now rejects non-ACTIVE
  accounts (`DepositServiceImpl.java:55`). Add a test for a FROZEN account (nothing saved), then update the comment.

### Ledger

- [x] `ledger/service/PostingServiceTest.java:109` — Missing cases from `createPostings`:
  - an unknown journal entry gives 404 and nothing is saved
  - an unknown account gives "Account X not found"
  - a posting whose currency differs from its account gives 400
  - fewer than two postings gives "At least two postings are required"
  - balancing is per currency: a USD debit against an EUR credit of the same amount gives 400
  - `getPosting` for someone else's posting gives 404
- [x] `ledger/service/PostingServiceTest.java:116` — Done: `PostingServiceTest` checks balances, and
  `PostingControllerTest` checks them against Postgres.

### Users

- [ ] `user/controller/UserControllerTest.java:135` — Missing endpoint tests:
  - [x] `POST /user/create`: 201; the password hash never appears in the response; a duplicate email in different case
    gives 409.
  - `GET /user` paging (page/size) and `@Max(100)` on size give 400 with the parameter name.
  - `UserSearchRequest` / `UserSortField` are never used in main code; either wire them into `GET /user` and test them,
    or delete them.
- [x] `user/service/AppUserServiceTest.java:4` — `OutboxEvent` and `AppUserServiceImpl` (same package) are unused
  imports.
- [x] `user/service/AppUserServiceTest.java:88` — These compare the mapper stub with itself. Check what the service
  actually does instead: capture the saved user and assert `passwordHash` == encoder output (not the raw password) and
  that the email is normalized. Use a mixed-case email in the request so normalization is really tested.
- [x] `user/service/AppUserServiceTest.java:110` — Assert the 409 status, and verify nothing was saved and no event was
  recorded.
- [ ] `user/service/AppUserServiceTest.java:128` — Missing: the race where `saveAndFlush` throws
  `DataIntegrityViolationException` should give 409 "Email is already registered"; and `getUsers` paging (sorted by id).

### Holds

- [ ] `hold/controller/HoldControllerSecurityTest.java:201` — Missing role cases: ADMIN capture/release (both should be
  403), and noUser for capture/release/expire.
- [ ] `hold/controller/HoldControllerSecurityTest.java:202` — No test sends a real JWT through
  `JwtAuthenticationFilter` (valid, expired, denylisted token). Cover that once in a security test, not per controller.
- [ ] `hold/service/HoldServiceTest.java:139` — Also verify that `sumAmountByAccountIdAndStatus` received "now" and not
  some other Instant. Expired-but-still-ACTIVE holds must not count against the balance, and with `any(Instant.class)`
  that bug would go unnoticed.
- [ ] `hold/service/HoldServiceTest.java:297` — Missing: a capture whose destination is a system account
  (`findUserAccountById` hides it, so it should be a 404).
- [ ] `hold/service/HoldServiceTest.java:479` — Missing: `releaseHold` / `expireHold` for a hold that doesn't exist
  (404).
- [ ] `hold/service/HoldServiceTest.java:480` — Missing: the access check on capture/release, if merchants should only
  touch holds they created (currently any MERCHANT may capture any hold).
- [ ] `hold/service/HoldServiceTest.java:482` — The FOR UPDATE locking and the active-hold sum are only mocked here. Add
  a Testcontainers test where two holds race for the last of the balance and only one succeeds.

### Transfers

- [x] `transfer/service/TransferServiceTest.java:127` — Missing: an unknown destination account. Its message currently
  names the source id (`TransferServiceImpl.java:292`); fix that first, then assert "Account 2 not found".
- [x] `transfer/controller/TransferControllerTest.java:193` — Add these once `createTransfer` is fixed:
  - A user who doesn't own `idFromAccount` gets 404. Today the service doesn't check this, so anyone can create a
    transfer from someone else's account.
  - A transfer to the same account gives 400. Today `chk_transfer_distinct` turns it into a 500.
  - `request.currency` that differs from both accounts' currency gives 400.
  - An unknown destination account gives a 404 naming that account; the message currently uses the source id.
- [ ] No TODO comment yet: fees and reversal in `TransferProcessingServiceImpl` have no tests. Cover the fee postings
  (including a fee that rounds to 0), a full reversal (fee refunded, balances back), and the 409 when the recipient has
  spent the money.

### Auth

- [ ] `auth/RefreshTokenServiceTest.java` — The class is empty. Add tests for issuing, rotating and revoking refresh
  tokens, or delete it.

## Other services

- [ ] Not yet tested: `NotificationRepository` against Postgres (`markAllRead` touches only that user's unread rows,
  unread count, newest first), `NotificationController` security (401 without a token, user id taken from the
  `user_id` claim), and bank-sim's `WebhookSender` (backoff until 2xx, gives up after `maxAttempts`, DROP and
  DUPLICATE modes, `resend` of a PENDING payment gives 409). notification-service now has Testcontainers (Kafka, Postgres).

- [ ] `services/bank-sim/src/main/java/com/waller/bank_sim/payment/service/PaymentServiceImpl.java:42` — Reject a
  reused key whose amount/currency differ from the original request (422).
- [ ] `services/notification-service/src/main/java/com/waller/notification_service/config/SecurityConfig.java:31` —
  Refresh on an unknown `kid` (key rotation) without evicting the keys that still work.
- [x] `services/notification-service/src/main/java/com/waller/notification_service/config/KafkaConsumerConfig.java:17` —
  Done: the DLT publisher uses Boot's `KafkaTemplate`, with String serializers set under `spring.kafka.producer` in
  `application.yaml`. Covered by `WalletEventDlqTest` (Road to done, Must do 3).
- [ ] `services/notification-service/src/main/java/com/waller/notification_service/notification/service/NotificationTemplates.java:27` —
  DepositSucceeded / DepositFailed templates. wallet-service now emits both, so this is unblocked.
- [ ] `services/notification-service/src/main/java/com/waller/notification_service/notification/service/NotificationServiceImpl.java:35` —
  Push channel (email / web push / SSE) goes here.
