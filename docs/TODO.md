# TODOs

All `TODO` comments in the codebase, collected on 2026-10-15 (78 total) and updated on 2026-10-17. Paths are relative
to the repo root; `wallet-service` code lives under `src/main/java/com/waller/wallet_platform/` and its tests under
`src/test/java/com/waller/wallet_platform/`. Line numbers are from the first collection and may have drifted.

## Road to done

"Done" here means: the three money flows (deposit, transfer, hold) work end to end, the whole system starts with one
command, and the README explains it. The sections below this one list every TODO; this one picks the order.

### Must do

1. **Deposits never reach the bank.** `DepositProcessingServiceImpl.submitToBank` exists but nothing calls it, so a
   deposit stays PENDING and bank-sim never sees it. Make `gateway_reference` nullable (migration V4) or store
   bank-sim's reference in a new column, call `submitToBank` after `createDeposit` commits, and set `expiresAt` on
   creation. The webhook and reconciliation code behind it is already written. See the Deposits items `:65`, `:70`
   and `:169`.
2. **One-command startup.** `compose.yaml` only starts Postgres and Kafka. Add Dockerfiles for wallet-service,
   bank-sim and notification-service, and compose entries that wire them together: bank URL and the shared webhook
   secret (`BANK_SIM_WEBHOOK_SECRET`) between wallet-service and bank-sim, Kafka and Postgres for
   notification-service.
3. **Check notification-service's dead-letter publishing** (`KafkaConsumerConfig.java:17`). If the DLT publisher can't
   send, one malformed record is retried forever instead of being parked. Prove it with a test that sends a record
   without an `event-id` header and expects it on `<topic>.DLT`.
4. **README.** It is one line, and `HELP.md` is the Spring Initializr default (delete it). Cover what the system does,
   the architecture (wallet-service → outbox → Kafka → notification-service; bank-sim → signed webhook →
   wallet-service), how to run it, and how to use the bank-sim scenarios (`/control`) to demonstrate failures.

### Should do

- Idempotency-Key on `POST /transfer/create` (Transfers `:55`).
- Bank error handling in `BankClient`: retry, 4xx vs 5xx, 404 (Bank integration `:39`, `:41`, `:54`). Only matters
  once deposits are submitted.
- Decide whether deposits into a FROZEN account are allowed (Deposits `:71`).
- Admin endpoint for transfer reversal, and lock both accounts in id order there so it can't deadlock with
  `process()` (Transfers `:96`, `:109`).
- Event schemas for `DepositSucceeded|DepositFailed|DepositExpired` and `TransferCompleted|TransferFailed|TransferReversed`
  (Deposits `:41`, Transfers `:38`), plus notification templates for deposit results (Other services).
- One shared Postgres container for the `@SpringBootTest` classes (Tests, Shared setup `:10`); the suite gets slower
  with every controller test.

### Can wait (list as known limitations in the README)

Multi-instance job claiming (ShedLock / SKIP LOCKED), metrics and alerts, fees, transfer limits and fraud checks,
partial reversals, webhook secret rotation, push notifications, rate limiting the webhook endpoint, and the test
cleanup items below.

## wallet-service — main code

### Deposits

- [ ] `deposit/repository/DepositRepository.java:41` — `idx_deposit_pending` is on `(status, expires_at)`; add one on
  `(status, updated_at)` if this gets slow.
- [ ] `deposit/job/DepositReconciliationJob.java:50` — With several wallet-service instances every one runs this; add
  ShedLock or SKIP LOCKED claiming.
- [ ] `deposit/job/DepositReconciliationJob.java:61` — Metrics: count of stale PENDING deposits and oldest age, for an
  alert.
- [ ] `deposit/job/DepositReconciliationJob.java:69` — A deposit whose `submitToBank` never got a response has no bank
  reference yet: resubmit it (same idempotency key, so no double charge) instead of looking it up.
- [ ] `deposit/service/DepositProcessingServiceImpl.java:41` — Add
  `contracts/events/DepositSucceeded|DepositFailed|DepositExpired.v1.schema.json` and the README table rows.
- [ ] `deposit/service/DepositProcessingServiceImpl.java:65` — Wire this in. `createDeposit` still takes
  `gatewayReference` from the client, but with bank-sim the reference only exists after `POST /payments`. Options: make
  `gateway_reference` nullable (migration V4, the unique constraint still works since NULLs don't collide), or keep the
  client-supplied value as our own reference and store bank-sim's in a new column. Then call `submitToBank` after
  `createDeposit` commits (directly, or from a DepositCreated consumer / outbox-style job so a crash in between is
  retried).
- [ ] `deposit/service/DepositProcessingServiceImpl.java:70` — Set `deposit.expiresAt` on creation (config:
  `deposit.pending-ttl`) so `expire()` has something to check.
- [ ] `deposit/service/DepositProcessingServiceImpl.java:71` — Check the account is ACTIVE before sending money in (see
  the TODO in `DepositServiceTest`).
- [ ] `deposit/service/DepositProcessingServiceImpl.java:96` — Alert: a webhook for a missing deposit means money with
  no deposit.
- [ ] `deposit/service/DepositProcessingServiceImpl.java:102` — Alert / park for manual review on an amount mismatch.
  Never credit an amount the user didn't ask for.
- [ ] `deposit/service/DepositProcessingServiceImpl.java:111` — Decide: credit anyway (late success) or refund through
  the bank; at least alert.
- [ ] `deposit/service/DepositProcessingServiceImpl.java:136` — Ask the bank first (`getPayment`) so a payment that did
  succeed isn't expired; or cancel it at the bank.
- [x] `deposit/service/DepositProcessingServiceImpl.java:153` — Balances only change once `PostingServiceImpl` applies
  postings. Done: postings now move balances. Remove the comment.
- [ ] `deposit/service/DepositProcessingServiceImpl.java:154` — Fees: a FEE posting to `SystemAccountKind.FEES`,
  credited amount = amount - fee.
- [ ] `deposit/service/DepositProcessingServiceImpl.java:169` — Once `gateway_reference` is set by `submitToBank`, also
  require it to equal `result.reference()`.

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
- [ ] `transfer/service/TransferProcessingServiceImpl.java:38` — Add
  `contracts/events/TransferCompleted|TransferFailed|TransferReversed.v1.schema.json` and the README rows.
- [x] `transfer/service/TransferProcessingServiceImpl.java:52` — Effectively done: `TransferProcessingJob` processes
  PENDING transfers every `transfer.processing.poll-interval` (asynchronous). Remove the comment, or switch to
  synchronous processing if the response should already say COMPLETED/FAILED. Original note: Wire this in. Either call it from `createTransfer` in
  the same transaction (synchronous: the response already says COMPLETED/FAILED), or leave `createTransfer` as is and
  let `TransferProcessingJob` pick it up (asynchronous: the client polls `GET /transfer/{id}`). Synchronous is simpler;
  async allows limits/fraud checks.
- [ ] `transfer/service/TransferProcessingServiceImpl.java:55` — Idempotency-Key on `POST /transfer/create`
  (`common/idempotency` exists), so a retried request doesn't create and book a second transfer.
- [x] `transfer/service/TransferProcessingServiceImpl.java:86` — Balances only change once `PostingServiceImpl` applies
  postings; until then the funds check reads a balance that never moves. Done; remove the comment.
- [ ] `transfer/service/TransferProcessingServiceImpl.java:88` — Fees: a FEE posting from the sender to
  `SystemAccountKind.FEES`.
- [ ] `transfer/service/TransferProcessingServiceImpl.java:96` — Expose as an admin-only endpoint
  (`POST /transfer/{id}/reverse`), like the journal entry controller.
- [ ] `transfer/service/TransferProcessingServiceImpl.java:109` — Lock both accounts in id order as in `process()`, and
  decide what happens when the recipient has already spent the money: refuse (409), or allow a negative balance and
  collect it later.
- [ ] `transfer/service/TransferProcessingServiceImpl.java:111` — Partial reversals (amount < transfer amount) would
  need a `reversed_amount` column.
- [ ] `transfer/service/TransferProcessingServiceImpl.java:146` — Limits (per transfer, daily per user) and a
  fraud/velocity check.

### Bank integration

- [ ] `bank/webhook/BankWebhookVerifier.java:60` — Accept a second (previous) secret during rotation, so bank-sim and
  wallet-service can switch separately.
- [ ] `bank/webhook/BankWebhookController.java:45` — Store failed webhooks somewhere (dead-letter table) and alert
  instead of only logging.
- [ ] `bank/webhook/BankWebhookController.java:50` — Rate limit / size limit this endpoint like `/api/auth/**`; it is
  public.
- [ ] `bank/client/BankClient.java:39` — Retry 503s and timeouts here with the same key (bank-sim's failureRate /
  lostResponseRate scenarios), or leave it to the reconciliation job. Decide which, and don't do both.
- [ ] `bank/client/BankClient.java:41` — Map errors: 4xx means the request is wrong and retrying won't help (mark the
  deposit FAILED); 5xx or timeout means the outcome is unknown (keep it PENDING).
- [ ] `bank/client/BankClient.java:54` — 404 means the bank never stored the payment (e.g. failed before storing);
  handle it in the caller.

## wallet-service — tests

### Shared setup

- [ ] `support/TestData.java:10` — Each `@SpringBootTest` class (Account/Deposit/User/TransferControllerTest) starts its
  own Postgres container and Spring context, and copies the same `@Container`/`@LocalServerPort`/`RestAssured.port`
  setup. Move it into one abstract base class (a static container started once, plus a shared cleanup helper) so the
  context and container are reused.
- [ ] `support/TestData.java:13` — Deposit and outbox tests build entities by hand in `setUp()`; add factories here
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
- [ ] `deposit/service/DepositServiceTest.java:43` — `PER_METHOD` is already JUnit's default; remove `@TestInstance`
  here, in `AccountServiceTest` and `AppUserServiceTest`. Also remove the unused imports (`DepositServiceImpl` is in
  this package).
- [ ] `deposit/service/DepositServiceTest.java:117` — Argument order is reversed (expected value goes first), so a
  failure message would be misleading.
- [ ] `deposit/service/DepositServiceTest.java:121` — This should be `account.getId()`. It passes only because the user
  and account ids are both 1.
- [ ] `deposit/service/DepositServiceTest.java:136` — Also `verifyNoInteractions(repository, outboxEventService)`.
  Without it, a deposit saved before the account check would still pass.
- [ ] `deposit/service/DepositServiceTest.java:172` — Missing cases: currency mismatch (400, nothing saved), and
  `getDeposit` for your own deposit, someone else's, and a missing one. Also reformat the last two tests; their
  indentation is off.
- [ ] `deposit/service/DepositServiceTest.java:174` — `createDeposit` doesn't check account status (`HoldServiceImpl`
  rejects non-ACTIVE accounts). Decide whether deposits into a FROZEN account should be allowed, then add a test either
  way.

### Ledger

- [x] `ledger/service/PostingServiceTest.java:109` — Missing cases from `createPostings`:
  - an unknown journal entry gives 404 and nothing is saved
  - an unknown account gives "Account X not found"
  - a posting whose currency differs from its account gives 400
  - fewer than two postings gives "At least two postings are required"
  - balancing is per currency: a USD debit against an EUR credit of the same amount gives 400
  - `getPosting` for someone else's posting gives 404
- [x] `ledger/service/PostingServiceTest.java:116` — Done: `PostingServiceTest` checks balances, and
  `PostingControllerTest` checks them against Postgres. Original note: Once `PostingServiceImpl` applies postings to balances, add tests
  that check the balances, ideally against Postgres.

### Users

- [ ] `user/controller/UserControllerTest.java:91` — Missing endpoint tests:
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
- [ ] `hold/service/HoldServiceTest.java:140` — Also verify that `sumAmountByAccountIdAndStatus` received "now" and not
  some other Instant. Expired-but-still-ACTIVE holds must not count against the balance, and with `any(Instant.class)`
  that bug would go unnoticed.
- [ ] `hold/service/HoldServiceTest.java:298` — Missing: a capture whose destination is a system account
  (`findUserAccountById` hides it, so it should be a 404).
- [ ] `hold/service/HoldServiceTest.java:480` — Missing: `releaseHold` / `expireHold` for a hold that doesn't exist
  (404).
- [ ] `hold/service/HoldServiceTest.java:481` — Missing: the access check on capture/release, if merchants should only
  touch holds they created (currently any MERCHANT may capture any hold).
- [ ] `hold/service/HoldServiceTest.java:483` — The FOR UPDATE locking and the active-hold sum are only mocked here. Add
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

### Auth

- [ ] `auth/RefreshTokenServiceTest.java` — The class is empty. Add tests for issuing, rotating and revoking refresh
  tokens, or delete it.

## Other services

- [ ] Not yet tested: `NotificationRepository` against Postgres (`markAllRead` touches only that user's unread rows,
  unread count, newest first), `NotificationController` security (401 without a token, user id taken from the
  `user_id` claim), and bank-sim's `WebhookSender` (backoff until 2xx, gives up after `maxAttempts`, DROP and
  DUPLICATE modes, `resend` of a PENDING payment gives 409). notification-service has no Testcontainers dependency yet.

- [ ] `services/bank-sim/src/main/java/com/waller/bank_sim/payment/service/PaymentServiceImpl.java:42` — Reject a
  reused key whose amount/currency differ from the original request (422).
- [ ] `services/notification-service/src/main/java/com/waller/notification_service/config/SecurityConfig.java:31` —
  Refresh on an unknown `kid` (key rotation) without evicting the keys that still work.
- [ ] `services/notification-service/src/main/java/com/waller/notification_service/config/KafkaConsumerConfig.java:17` —
  `KafkaTemplate` needs producer config (string serializers) for the DLT publisher.
- [ ] `services/notification-service/src/main/java/com/waller/notification_service/notification/service/NotificationTemplates.java:27` —
  DepositSucceeded / DepositFailed templates once wallet-service emits them from the bank webhook.
- [ ] `services/notification-service/src/main/java/com/waller/notification_service/notification/service/NotificationServiceImpl.java:35` —
  Push channel (email / web push / SSE) goes here.
