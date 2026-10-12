package cl.duoc.eventomax.audit.dto;

import java.time.LocalDateTime;

public class AuditEventResponse {
    private String eventId;
    private String actor;
    private String type;
    private LocalDateTime timestamp;
    private String details;
    private String traceId;
    private String correlationId;

    public AuditEventResponse() {
    }

    public AuditEventResponse(String eventId, String actor, String type, LocalDateTime timestamp, String details) {
        this(eventId, actor, type, timestamp, details, null, null);
    }

    public AuditEventResponse(String eventId, String actor, String type, LocalDateTime timestamp, String details,
                              String traceId, String correlationId) {
        this.eventId = eventId;
        this.actor = actor;
        this.type = type;
        this.timestamp = timestamp;
        this.details = details;
        this.traceId = traceId;
        this.correlationId = correlationId;
    }

    public String getEventId() { return eventId; }
    public String getActor() { return actor; }
    public String getType() { return type; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getDetails() { return details; }
    public String getTraceId() { return traceId; }
    public String getCorrelationId() { return correlationId; }
}
