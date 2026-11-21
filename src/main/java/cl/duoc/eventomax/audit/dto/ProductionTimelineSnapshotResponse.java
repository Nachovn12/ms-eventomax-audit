package cl.duoc.eventomax.audit.dto;

import java.time.LocalDateTime;

/**
 * Último estado proyectado por producción a partir del tópico compactado {@code audit.timeline}.
 */
public record ProductionTimelineSnapshotResponse(
        String productionId,
        String lastEventId,
        String lastEventType,
        String currentStatus,
        String productionName,
        String actor,
        String actorDisplayName,
        String source,
        String traceId,
        String correlationId,
        LocalDateTime lastOccurredAt,
        LocalDateTime updatedAt,
        String details
) {
    public ProductionTimelineSnapshotResponse(
            String productionId,
            String lastEventId,
            String lastEventType,
            String currentStatus,
            String productionName,
            String actor,
            String source,
            String traceId,
            String correlationId,
            LocalDateTime lastOccurredAt,
            LocalDateTime updatedAt,
            String details) {
        this(productionId, lastEventId, lastEventType, currentStatus, productionName,
                actor, null, source, traceId, correlationId, lastOccurredAt, updatedAt, details);
    }
}
