-- Optimize timeline query ordering by event_timestamp and id
CREATE INDEX IF NOT EXISTS idx_audit_event_timestamp_id ON audit_event (event_timestamp DESC, id DESC);
