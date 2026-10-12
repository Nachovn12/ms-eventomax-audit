-- EMX-69: Add trace_id and correlation_id to audit_event for end-to-end distributed tracing.
-- Nullable columns preserve backwards compatibility with historical audit events.
ALTER TABLE audit_event ADD COLUMN trace_id VARCHAR(255);
ALTER TABLE audit_event ADD COLUMN correlation_id VARCHAR(255);
