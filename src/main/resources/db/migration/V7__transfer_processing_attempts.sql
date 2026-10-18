-- Failed processing attempts of a PENDING transfer, so the job backs off and gives up instead of retrying forever.

ALTER TABLE transfer
    ADD COLUMN attempts        INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ;           -- null: due now

-- The job reads PENDING transfers oldest first; ordering on the index avoids sorting the whole backlog
DROP INDEX idx_transfer_pending;
CREATE INDEX idx_transfer_pending
    ON transfer (created_at, id) WHERE status = 'PENDING';
