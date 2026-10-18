-- One account per user and currency (see docs/decisions.md). System accounts have no user and keep their own
-- uq_account_system_kind_currency. Fails if a user already has two accounts in one currency: merge those by hand
-- (move the balance with a journal entry), don't drop the constraint.
CREATE UNIQUE INDEX uq_account_user_currency ON account (user_id, currency) WHERE user_id IS NOT NULL;

-- Covered by the unique index, which starts with user_id
DROP INDEX idx_account_user_id;
