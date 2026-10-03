BEGIN;

-- =============================================================================
-- 1. ENUM TYPES
-- =============================================================================

CREATE TYPE account_type       AS ENUM ('USER', 'SYSTEM');
CREATE TYPE account_status     AS ENUM ('ACTIVE', 'FROZEN', 'CLOSED');

CREATE TYPE journal_entry_type AS ENUM (
    'TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'FEE',
    'HOLD_CAPTURE', 'HOLD_RELEASE', 'REVERSAL'
);
CREATE TYPE posting_direction  AS ENUM ('DEBIT', 'CREDIT');

CREATE TYPE transfer_status    AS ENUM ('PENDING', 'COMPLETED', 'FAILED', 'REVERSED');
CREATE TYPE deposit_status     AS ENUM ('PENDING', 'SUCCEEDED', 'FAILED', 'EXPIRED');
CREATE TYPE hold_status        AS ENUM ('ACTIVE', 'CAPTURED', 'RELEASED', 'EXPIRED');

CREATE TYPE outbox_status      AS ENUM ('PENDING', 'PUBLISHED', 'FAILED');

CREATE TYPE user_role AS ENUM ('MERCHANT', 'USER', 'ADMIN');


-- =============================================================================
-- 2. CORE: USERS & ACCOUNTS
-- =============================================================================

CREATE TABLE app_user (
    id            BIGSERIAL    PRIMARY KEY,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          user_role    NOT NULL DEFAULT 'USER'
);

CREATE TABLE account (
    id         BIGSERIAL      PRIMARY KEY,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    balance    BIGINT         NOT NULL DEFAULT 0,   -- minor units
    currency   CHAR(3)        NOT NULL,
    version    INTEGER        NOT NULL DEFAULT 0,   -- JPA @Version
    type       account_type   NOT NULL DEFAULT 'USER',
    status     account_status NOT NULL DEFAULT 'ACTIVE',
    user_id    BIGINT         NOT NULL,

    CONSTRAINT fk_account_user
        FOREIGN KEY (user_id)
        REFERENCES app_user (id)
        ON DELETE RESTRICT
);

CREATE INDEX idx_account_user_id ON account (user_id);


-- =============================================================================
-- 3. LEDGER: JOURNAL ENTRIES & POSTINGS
-- =============================================================================

CREATE TABLE journal_entry (
    id              BIGSERIAL          PRIMARY KEY,
    created_at      TIMESTAMPTZ        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    entry_type      journal_entry_type NOT NULL,
    description     VARCHAR(255),
    reference_type  VARCHAR(50),       -- 'transfer' | 'deposit' | 'hold' | ...
    reference_id    BIGINT             -- no FK: polymorphic back-reference
);

CREATE INDEX idx_journal_entry_reference
    ON journal_entry (reference_type, reference_id);

CREATE TABLE posting (
    id               BIGSERIAL         PRIMARY KEY,
    journal_entry_id BIGINT            NOT NULL,
    account_id       BIGINT            NOT NULL,
    direction        posting_direction NOT NULL,
    amount           BIGINT            NOT NULL CHECK (amount > 0),  -- minor units
    currency         CHAR(3)           NOT NULL,
    created_at       TIMESTAMPTZ       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_posting_entry
        FOREIGN KEY (journal_entry_id)
        REFERENCES journal_entry (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_posting_account
        FOREIGN KEY (account_id)
        REFERENCES account (id)
        ON DELETE RESTRICT
);

CREATE INDEX idx_posting_account_created ON posting (account_id, created_at DESC);
CREATE INDEX idx_posting_entry           ON posting (journal_entry_id);


-- =============================================================================
-- 4. FLOWS: TRANSFER / DEPOSIT / HOLD
-- =============================================================================

CREATE TABLE transfer (
    id               BIGSERIAL       PRIMARY KEY,
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    from_account_id  BIGINT          NOT NULL,
    to_account_id    BIGINT          NOT NULL,
    amount           BIGINT          NOT NULL CHECK (amount > 0),
    currency         CHAR(3)         NOT NULL,
    status           transfer_status NOT NULL DEFAULT 'PENDING',
    journal_entry_id BIGINT,
    failure_reason   VARCHAR(255),

    CONSTRAINT fk_transfer_from
        FOREIGN KEY (from_account_id)
        REFERENCES account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfer_to
        FOREIGN KEY (to_account_id)
        REFERENCES account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfer_entry
        FOREIGN KEY (journal_entry_id)
        REFERENCES journal_entry (id) ON DELETE RESTRICT,
    CONSTRAINT chk_transfer_distinct
        CHECK (from_account_id <> to_account_id)
);

CREATE INDEX idx_transfer_from   ON transfer (from_account_id, created_at DESC);
CREATE INDEX idx_transfer_to     ON transfer (to_account_id,   created_at DESC);
CREATE INDEX idx_transfer_pending
    ON transfer (status) WHERE status = 'PENDING';

CREATE TABLE deposit (
    id                BIGSERIAL      PRIMARY KEY,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    account_id        BIGINT         NOT NULL,
    amount            BIGINT         NOT NULL CHECK (amount > 0),
    currency          CHAR(3)        NOT NULL,
    gateway           VARCHAR(50)    NOT NULL,      -- 'stripe', 'adyen', ...
    gateway_reference VARCHAR(255)   NOT NULL,      -- PSP txn id / charge id
    status            deposit_status NOT NULL DEFAULT 'PENDING',
    journal_entry_id  BIGINT,
    failure_reason    VARCHAR(255),
    expires_at        TIMESTAMPTZ,

    CONSTRAINT uq_deposit_gateway_ref
        UNIQUE (gateway, gateway_reference),
    CONSTRAINT fk_deposit_account
        FOREIGN KEY (account_id)
        REFERENCES account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_deposit_entry
        FOREIGN KEY (journal_entry_id)
        REFERENCES journal_entry (id) ON DELETE RESTRICT
);

CREATE INDEX idx_deposit_account
    ON deposit (account_id, created_at DESC);
CREATE INDEX idx_deposit_pending
    ON deposit (status, expires_at) WHERE status = 'PENDING';

CREATE TABLE hold (
    id                BIGSERIAL    PRIMARY KEY,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    account_id        BIGINT       NOT NULL,
    amount            BIGINT       NOT NULL CHECK (amount > 0),
    currency          CHAR(3)      NOT NULL,
    status            hold_status  NOT NULL DEFAULT 'ACTIVE',
    expires_at        TIMESTAMPTZ  NOT NULL,
    captured_entry_id BIGINT,
    released_at       TIMESTAMPTZ,

    CONSTRAINT fk_hold_account
        FOREIGN KEY (account_id)
        REFERENCES account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_hold_entry
        FOREIGN KEY (captured_entry_id)
        REFERENCES journal_entry (id) ON DELETE RESTRICT
);

CREATE INDEX idx_hold_account_active
    ON hold (account_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_hold_expiry
    ON hold (expires_at) WHERE status = 'ACTIVE';


-- =============================================================================
-- 5. INFRASTRUCTURE: IDEMPOTENCY & OUTBOX
-- =============================================================================

CREATE TABLE idempotency_key (
    id              BIGSERIAL    PRIMARY KEY,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id         BIGINT       NOT NULL,
    key             VARCHAR(255) NOT NULL,      -- from Idempotency-Key header
    endpoint        VARCHAR(255) NOT NULL,      -- e.g. 'POST /transfers'
    request_hash    CHAR(64)     NOT NULL,      -- SHA-256 of canonical body
    response_status INTEGER,
    response_body   JSONB,
    expires_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_idem_user_key UNIQUE (user_id, key),
    CONSTRAINT fk_idem_user
        FOREIGN KEY (user_id)
        REFERENCES app_user (id) ON DELETE CASCADE
);

CREATE INDEX idx_idem_expiry ON idempotency_key (expires_at);

CREATE TABLE outbox_event (
    id             BIGSERIAL     PRIMARY KEY,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at   TIMESTAMPTZ,
    aggregate_type VARCHAR(50)   NOT NULL,      -- 'transfer', 'deposit', ...
    aggregate_id   BIGINT        NOT NULL,
    event_type     VARCHAR(100)  NOT NULL,      -- 'transfer.completed'
    payload        JSONB         NOT NULL,
    status         outbox_status NOT NULL DEFAULT 'PENDING',
    attempts       INTEGER       NOT NULL DEFAULT 0,
    last_error     TEXT,

    CONSTRAINT uq_outbox_dedup
        UNIQUE (aggregate_type, aggregate_id, event_type)
);

CREATE INDEX idx_outbox_pending
    ON outbox_event (created_at) WHERE status = 'PENDING';

COMMIT;