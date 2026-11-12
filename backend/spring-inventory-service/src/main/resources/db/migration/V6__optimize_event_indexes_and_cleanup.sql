CREATE INDEX idx_processed_event_processed_at ON processed_event USING BRIN (processed_at);
