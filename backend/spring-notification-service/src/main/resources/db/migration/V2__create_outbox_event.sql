CREATE TABLE outbox_event (
    event_id UUID PRIMARY KEY,
    topic VARCHAR(120) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    aggregate_id VARCHAR(120) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    traceparent VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    sequence_id BIGSERIAL NOT NULL
);

CREATE INDEX idx_outbox_event_pending ON outbox_event (occurred_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_event_aggregate_pending
    ON outbox_event (topic, aggregate_id, occurred_at, event_id)
    WHERE published_at IS NULL;
CREATE INDEX idx_outbox_event_published ON outbox_event (published_at) WHERE published_at IS NOT NULL;
CREATE INDEX idx_outbox_pending_sequence ON outbox_event (sequence_id) WHERE published_at IS NULL;
