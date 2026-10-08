package cl.duoc.eventomax.audit.dto;

import java.time.LocalDateTime;

public class AuditEventResponse {
    private String eventId;
    private String actor;
    private String type;
    private LocalDateTime timestamp;
    private String details;

    public AuditEventResponse(String eventId, String actor, String type, LocalDateTime timestamp, String details) {
        this.eventId = eventId;
        this.actor = actor;
        this.type = type;
        this.timestamp = timestamp;
        this.details = details;
    }

    public String getEventId() { return eventId; }
    public String getActor() { return actor; }
    public String getType() { return type; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getDetails() { return details; }
}
