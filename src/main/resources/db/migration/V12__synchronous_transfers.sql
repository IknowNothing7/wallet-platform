-- Transfers are booked in the request that creates them (TransferProcessingServiceImpl.book), so a transfer row is
-- only ever written as COMPLETED or FAILED. PENDING, the retry bookkeeping of V7 and the processing job are gone.

-- Stop the old version (and so its job) before deploying. Anything it left PENDING is failed here, without a
-- TransferFailed event: no money moved for it.
UPDATE transfer
SET status         = 'FAILED',
    failure_reason = 'Not processed before transfers became synchronous',
    updated_at     = CURRENT_TIMESTAMP
WHERE status = 'PENDING';

DROP INDEX idx_transfer_pending;

ALTER TABLE transfer
    DROP COLUMN attempts,
    DROP COLUMN next_attempt_at,
    ALTER COLUMN status DROP DEFAULT;

-- Postgres can't drop an enum value, so the type is swapped for one without PENDING
ALTER TYPE transfer_status RENAME TO transfer_status_old;
CREATE TYPE transfer_status AS ENUM ('COMPLETED', 'FAILED', 'REVERSED');
ALTER TABLE transfer
    ALTER COLUMN status TYPE transfer_status USING status::text::transfer_status;
DROP TYPE transfer_status_old;

DELETE FROM shedlock WHERE name = 'transferProcessing';
