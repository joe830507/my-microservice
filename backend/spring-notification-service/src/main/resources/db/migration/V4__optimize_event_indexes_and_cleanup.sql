DROP INDEX IF EXISTS idx_outbox_event_pending;
DROP INDEX IF EXISTS idx_outbox_event_aggregate_pending;
CREATE INDEX idx_outbox_event_aggregate_pending_seq
    ON outbox_event (topic, aggregate_id, sequence_id) WHERE published_at IS NULL;
CREATE INDEX idx_processed_event_processed_at ON processed_event USING BRIN (processed_at);
CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
