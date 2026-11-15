-- Compaction read-model projection populated by TimelineEventConsumer (audit.timeline)
CREATE TABLE IF NOT EXISTS production_timeline_snapshot (
    production_id VARCHAR(255) PRIMARY KEY,
    last_event_id VARCHAR(255) NOT NULL,
    last_event_type VARCHAR(255) NOT NULL,
    current_status VARCHAR(64),
    production_name VARCHAR(255),
    actor VARCHAR(255) NOT NULL,
    source VARCHAR(255),
    trace_id VARCHAR(255),
    correlation_id VARCHAR(255),
    last_occurred_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    details TEXT
);

CREATE INDEX IF NOT EXISTS idx_timeline_snapshot_occurred_at
    ON production_timeline_snapshot (last_occurred_at DESC);
