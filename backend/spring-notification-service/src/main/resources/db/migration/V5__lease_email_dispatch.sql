ALTER TABLE notification_service.email_message
    DROP CONSTRAINT email_message_status_check,
    ADD CONSTRAINT email_message_status_check CHECK (status IN ('PENDING','SENDING','SENT','SKIPPED','FAILED')),
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN lease_token UUID;
CREATE INDEX idx_email_message_lease ON notification_service.email_message (lease_until) WHERE status='SENDING';
