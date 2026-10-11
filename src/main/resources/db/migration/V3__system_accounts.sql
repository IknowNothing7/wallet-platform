-- System accounts (BANK_CLEARING, FEES, HOLDS) are owned by the platform, not by a user:
-- user_id is NULL and system_kind says which one it is. One per kind and currency.

CREATE TYPE system_account_kind AS ENUM ('BANK_CLEARING', 'FEES', 'HOLDS');

ALTER TABLE account
    ALTER COLUMN user_id DROP NOT NULL,
    ADD COLUMN system_kind system_account_kind;

ALTER TABLE account
    ADD CONSTRAINT chk_account_owner CHECK (
        (type = 'USER'   AND user_id IS NOT NULL AND system_kind IS NULL) OR
        (type = 'SYSTEM' AND user_id IS NULL     AND system_kind IS NOT NULL)
    );

CREATE UNIQUE INDEX uq_account_system_kind_currency
    ON account (system_kind, currency) WHERE system_kind IS NOT NULL;
