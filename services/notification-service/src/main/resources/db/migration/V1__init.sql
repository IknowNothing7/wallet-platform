-- Inbox: one row per consumed event id, so a redelivered event is handled once
CREATE TABLE processed_event (
    event_id     BIGINT       PRIMARY KEY,
    event_type   VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Local projection of wallet accounts (from AccountCreated), used to find who to notify
CREATE TABLE account_owner (
    account_id BIGINT PRIMARY KEY,
    user_id    BIGINT NOT NULL
);

CREATE TABLE notification (
    id         BIGSERIAL    PRIMARY KEY,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    user_id    BIGINT       NOT NULL,
    event_id   BIGINT       NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    title      VARCHAR(255) NOT NULL,
    body       TEXT         NOT NULL,
    read_at    TIMESTAMPTZ,

    CONSTRAINT uq_notification_event_user UNIQUE (event_id, user_id)
);

CREATE INDEX idx_notification_user_created
    ON notification (user_id, created_at DESC);

CREATE INDEX idx_notification_user_unread
    ON notification (user_id) WHERE read_at IS NULL;
