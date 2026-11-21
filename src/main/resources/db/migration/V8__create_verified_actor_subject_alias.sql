-- Additive migration: cryptographically verified (issuer, audience, subject) -> canonical_actor_id (tid:oid) aliases
-- Never stores JWTs, display names, or emails; never modifies historical audit_event rows.
CREATE TABLE IF NOT EXISTS verified_actor_subject_alias (
    issuer VARCHAR(255) NOT NULL,
    audience VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    canonical_actor_id VARCHAR(128) NOT NULL,
    verified_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    PRIMARY KEY (issuer, audience, subject)
);

CREATE INDEX IF NOT EXISTS idx_verified_actor_alias_canonical
    ON verified_actor_subject_alias (canonical_actor_id);
