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
    private String source;

    public AuditEventResponse() {
    }

    public AuditEventResponse(String eventId, String actor, String type, LocalDateTime timestamp, String details) {
        this(eventId, actor, type, timestamp, details, null, null, null);
    }

    public AuditEventResponse(String eventId, String actor, String type, LocalDateTime timestamp, String details,
                              String traceId, String correlationId) {
        this(eventId, actor, type, timestamp, details, traceId, correlationId, null);
    }

    public AuditEventResponse(String eventId, String actor, String type, LocalDateTime timestamp, String details,
                              String traceId, String correlationId, String source) {
        this.eventId = eventId;
        this.actor = actor;
        this.type = type;
        this.timestamp = timestamp;
        this.details = details;
        this.traceId = traceId;
        this.correlationId = correlationId;
        this.source = source;
    }

    public String getEventId() { return eventId; }
    public String getActor() { return actor; }
    public String getType() { return type; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getDetails() { return details; }
    public String getTraceId() { return traceId; }
    public String getCorrelationId() { return correlationId; }
    public String getSource() { return source; }
}
