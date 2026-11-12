ALTER TABLE outbox_event ADD COLUMN sequence_id BIGSERIAL;
CREATE INDEX idx_outbox_pending_sequence ON outbox_event (sequence_id) WHERE published_at IS NULL;
