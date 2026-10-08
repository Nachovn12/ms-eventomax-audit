package cl.duoc.eventomax.audit.dto;

import java.time.LocalDateTime;

public class ProductionEventMessage {
    private String eventId;
    private String actor;
    private String type;
    private LocalDateTime timestamp;
    private String details;

    public ProductionEventMessage() {}

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
}
