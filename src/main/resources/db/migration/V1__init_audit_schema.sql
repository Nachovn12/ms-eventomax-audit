CREATE TABLE processed_event (
    event_id VARCHAR(255) PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL
);

CREATE TABLE audit_event (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    event_timestamp TIMESTAMP NOT NULL,
    details TEXT
);

CREATE INDEX idx_audit_event_actor ON audit_event (actor);
CREATE INDEX idx_audit_event_type ON audit_event (type);
CREATE INDEX idx_audit_event_timestamp ON audit_event (event_timestamp);
