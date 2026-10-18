-- deposit.gateway_reference may be unknown when the deposit is created.
-- uq_deposit_gateway_ref still applies; Postgres treats NULLs as distinct, so they don't collide.

ALTER TABLE deposit
    ALTER COLUMN gateway_reference DROP NOT NULL;
