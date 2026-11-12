DROP INDEX IF EXISTS idx_outbox_event_pending;
DROP INDEX IF EXISTS idx_outbox_event_aggregate_pending;
CREATE INDEX idx_outbox_event_aggregate_pending_seq
    ON outbox_event (topic, aggregate_id, sequence_id) WHERE published_at IS NULL;
CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
