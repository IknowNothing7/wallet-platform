-- The ledger's invariants, enforced by the schema rather than only by LedgerServiceImpl.
-- Fails on existing data that already breaks one of them (a negative user balance, a duplicated entry); fix that
-- data first, don't weaken the constraint.

-- Only platform accounts (BANK_CLEARING is negative by design) may go below zero
ALTER TABLE account
    ADD CONSTRAINT chk_account_balance_non_negative CHECK (type = 'SYSTEM' OR balance >= 0);

-- One entry of each type per business object: a transfer is booked once and reversed at most once, a deposit is
-- credited once, a hold captured once. A retried or racing booking fails here instead of moving money twice.
-- Rows without a reference never compare equal (NULLs are distinct), but LedgerServiceImpl requires one.
ALTER TABLE journal_entry
    ADD CONSTRAINT uq_journal_entry_reference UNIQUE (reference_type, reference_id, entry_type);

-- Covered by the unique constraint's index, which starts with the same two columns
DROP INDEX idx_journal_entry_reference;

-- Append-only: a correction is a new entry (e.g. REVERSAL), never an edit.
-- The trigger is what actually enforces it: privileges don't bind a superuser, and both compose.yaml and the
-- Testcontainers database connect as one. Row triggers don't fire on TRUNCATE, so test resets still work.
CREATE FUNCTION ledger_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_journal_entry_append_only
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();

CREATE TRIGGER trg_posting_append_only
    BEFORE UPDATE OR DELETE ON posting
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();

-- And for a non-superuser application role, the privileges as well. A migration that really has to rewrite the
-- ledger must drop the trigger (and GRANT the privilege back) inside itself and say why.
REVOKE UPDATE, DELETE ON journal_entry, posting FROM PUBLIC;
REVOKE UPDATE, DELETE ON journal_entry, posting FROM CURRENT_USER;
