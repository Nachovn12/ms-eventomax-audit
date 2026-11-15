-- EMX-EP3: Add source column to audit_event for full traceability ("quién, qué, cuándo, desde dónde")
ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS source VARCHAR(255);


