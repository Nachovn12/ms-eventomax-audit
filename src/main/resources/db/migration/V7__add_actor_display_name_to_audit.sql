-- Additive migration: optional human-readable display name for audit actor
ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS actor_display_name VARCHAR(150);
ALTER TABLE production_timeline_snapshot ADD COLUMN IF NOT EXISTS actor_display_name VARCHAR(150);
