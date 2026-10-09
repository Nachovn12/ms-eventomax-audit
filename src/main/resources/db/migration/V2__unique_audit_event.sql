-- Fail visibly if historical duplicates exist; never delete audit records automatically.
ALTER TABLE audit_event ADD CONSTRAINT uk_audit_event_event_id UNIQUE (event_id);
