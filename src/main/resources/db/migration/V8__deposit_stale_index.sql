-- DepositReconciliationJob and its stale-deposit gauges filter PENDING deposits on updated_at, oldest first;
-- idx_deposit_pending is on expires_at, so without this every run scans the PENDING backlog
CREATE INDEX idx_deposit_stale
    ON deposit (updated_at, id) WHERE status = 'PENDING';
