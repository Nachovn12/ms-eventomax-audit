package cl.duoc.eventomax.audit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductionStatusChangedPayload(
        Long productionId,
        String organizerId,
        String productionName,
        String previousStatus,
        String newStatus,
        LocalDateTime scheduledAt,
        String location,
        Instant occurredAt
) {}
