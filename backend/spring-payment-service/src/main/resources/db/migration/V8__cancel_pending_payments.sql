ALTER TABLE payment_service.payment DROP CONSTRAINT chk_payment_status;
ALTER TABLE payment_service.payment ADD CONSTRAINT chk_payment_status
    CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED'));

-- uq_payment_order_active 保持只限制 PENDING／SUCCEEDED。
CREATE TABLE processed_event (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(120) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_processed_event_processed_at ON processed_event USING BRIN (processed_at);
