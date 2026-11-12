CREATE SCHEMA IF NOT EXISTS notification_service;

CREATE TABLE notification_service.processed_event (
    event_id UUID PRIMARY KEY,
    event_type TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notification_service.user_contact (
    user_id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(200) NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notification_service.order_ref (
    order_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    total_amount NUMERIC(12,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notification_service.email_message (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    template VARCHAR(80) NOT NULL,
    order_id UUID,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'SKIPPED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error VARCHAR(500),
    skip_reason VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT uq_email_message_event_template UNIQUE (event_id, template)
);

CREATE INDEX idx_email_message_pending
    ON notification_service.email_message (status, next_attempt_at);
