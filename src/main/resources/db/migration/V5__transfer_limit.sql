-- Outgoing transfer limits, in minor units. A row with currency set is the default for that currency;
-- a row with account_id set overrides the default for that account (in the account's currency).
-- A NULL max means no limit of that kind. daily_max is a rolling 24h window of COMPLETED transfers.

CREATE TABLE transfer_limit (
    id               BIGSERIAL   PRIMARY KEY,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    currency         CHAR(3),
    account_id       BIGINT,
    per_transfer_max BIGINT      CHECK (per_transfer_max > 0),
    daily_max        BIGINT      CHECK (daily_max > 0),

    CONSTRAINT fk_transfer_limit_account
        FOREIGN KEY (account_id)
        REFERENCES account (id) ON DELETE CASCADE,
    CONSTRAINT chk_transfer_limit_scope
        CHECK ((currency IS NULL) <> (account_id IS NULL))
);

CREATE UNIQUE INDEX uq_transfer_limit_currency
    ON transfer_limit (currency) WHERE currency IS NOT NULL;
CREATE UNIQUE INDEX uq_transfer_limit_account
    ON transfer_limit (account_id) WHERE account_id IS NOT NULL;

-- 10,000.00 per transfer, 50,000.00 per 24h
INSERT INTO transfer_limit (currency, per_transfer_max, daily_max) VALUES
    ('USD', 1000000, 5000000),
    ('EUR', 1000000, 5000000);
