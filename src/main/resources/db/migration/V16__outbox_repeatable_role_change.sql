-- uq_outbox_dedup allowed one event of each type per aggregate, which fits lifecycle events (a transfer completes
-- once) but not UserRoleChanged: a user made MERCHANT and then USER again needs a second one, and the insert failed,
-- rolling the role change back. Repeatable event types are left out of the dedup; every other type keeps it.
-- V16 rather than V15, which is taken on the open-telemetry branch.
ALTER TABLE outbox_event DROP CONSTRAINT uq_outbox_dedup;

CREATE UNIQUE INDEX uq_outbox_dedup ON outbox_event (aggregate_type, aggregate_id, event_type)
    WHERE event_type <> 'UserRoleChanged';
