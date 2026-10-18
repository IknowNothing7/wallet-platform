-- A hold is a reservation: the sum of ACTIVE holds is subtracted from the available balance, and nothing is posted
-- until capture. So the HOLDS system account of V3 was never used. Swapping the type fails if one was ever created.

ALTER TYPE system_account_kind RENAME TO system_account_kind_old;
CREATE TYPE system_account_kind AS ENUM ('BANK_CLEARING', 'FEES');
ALTER TABLE account
    ALTER COLUMN system_kind TYPE system_account_kind USING system_kind::text::system_account_kind;
DROP TYPE system_account_kind_old;
