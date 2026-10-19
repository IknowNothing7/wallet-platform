-- W3C traceparent of the transaction that recorded the event, so OutboxPublisher sends it as part of that trace and
-- consumers join it through the Kafka traceparent header. Null for events recorded outside a trace (or before V15).
ALTER TABLE outbox_event ADD COLUMN trace_parent VARCHAR(55);
