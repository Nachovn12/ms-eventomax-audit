package cl.duoc.eventomax.audit.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class KafkaEventEnvelope {

    private String schemaVersion = "1.0";
    private UUID eventId;
    private String type;
    private Instant timestamp;
    private String traceId;
    private String correlationId;
    private String actor;
    private Map<String, Object> payload;

    public KafkaEventEnvelope() {}

    public KafkaEventEnvelope(String schemaVersion, UUID eventId, String type, Instant timestamp,
                              String traceId, String correlationId, String actor, Map<String, Object> payload) {
        this.schemaVersion = schemaVersion;
        this.eventId = eventId;
        this.type = type;
        this.timestamp = timestamp;
        this.traceId = traceId;
        this.correlationId = correlationId;
        this.actor = actor;
        this.payload = payload;
    }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
}
