-- Validly signed bank webhooks that could not be read. bank-sim would resend the same body forever, so the
-- request is acknowledged and the body kept here for someone to look at (alerted via wallet_deposit_alerts_total).

CREATE TABLE bank_webhook_dead_letter (
    id          BIGSERIAL    PRIMARY KEY,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason      VARCHAR(255) NOT NULL,
    body        TEXT         NOT NULL
);
