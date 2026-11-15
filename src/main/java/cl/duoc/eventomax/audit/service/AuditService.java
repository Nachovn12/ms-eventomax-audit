package cl.duoc.eventomax.audit.service;

import cl.duoc.eventomax.audit.dto.KafkaEventEnvelope;
import cl.duoc.eventomax.audit.model.AuditEvent;
import cl.duoc.eventomax.audit.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final String DEFAULT_SOURCE = "ms-eventomax-productions";

    private final AuditEventRepository auditEventRepository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditService(AuditEventRepository auditEventRepository, JdbcTemplate jdbc) {
        this.auditEventRepository = auditEventRepository;
        this.jdbc = jdbc;
    }

    @Transactional
    public void processEvent(AuditEvent event) {
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        // The unique insert serializes concurrent deliveries. A rollback releases the claim.
        int claimed = jdbc.update("""
                INSERT INTO processed_event (event_id, processed_at) VALUES (?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, event.getEventId(), LocalDateTime.now(Clock.systemUTC()));
        if (claimed == 0) {
            log.info("Event {} already processed, skipping", event.getEventId());
            return;
        }

        auditEventRepository.saveAndFlush(event);
    }

    /**
     * Proyecta de forma idempotente y monótona el último estado conocido de cada producción
     * a partir de los mensajes consumidos desde el tópico compactado {@code audit.timeline}.
     */
    @Transactional
    public void projectTimelineSnapshot(KafkaEventEnvelope envelope, String aggregateKey) {
        if (envelope == null || envelope.getEventId() == null || envelope.getType() == null
                || envelope.getType().isBlank() || envelope.getTimestamp() == null) {
            throw new IllegalArgumentException("Invalid envelope for timeline projection");
        }

        Map<String, Object> payload = envelope.getPayload();
        String productionId = resolveProductionId(payload, aggregateKey, envelope.getEventId().toString());
        String currentStatus = extractText(payload, "newStatus");
        if (currentStatus == null) {
            currentStatus = extractText(payload, "status");
        }
        String productionName = extractText(payload, "productionName");
        String actor = hasText(envelope.getActor()) ? envelope.getActor() : "system";
        String source = hasText(envelope.getSource()) ? envelope.getSource() : DEFAULT_SOURCE;
        LocalDateTime occurredAt = LocalDateTime.ofInstant(envelope.getTimestamp(), ZoneOffset.UTC);
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());

        String details = null;
        if (payload != null) {
            try {
                details = objectMapper.writeValueAsString(payload);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to serialize timeline snapshot payload", e);
            }
        }

        jdbc.update("""
                INSERT INTO production_timeline_snapshot (
                    production_id, last_event_id, last_event_type, current_status,
                    production_name, actor, source, trace_id, correlation_id,
                    last_occurred_at, updated_at, details
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (production_id) DO UPDATE SET
                    last_event_id = EXCLUDED.last_event_id,
                    last_event_type = EXCLUDED.last_event_type,
                    current_status = COALESCE(EXCLUDED.current_status, production_timeline_snapshot.current_status),
                    production_name = COALESCE(EXCLUDED.production_name, production_timeline_snapshot.production_name),
                    actor = EXCLUDED.actor,
                    source = COALESCE(EXCLUDED.source, production_timeline_snapshot.source),
                    trace_id = COALESCE(EXCLUDED.trace_id, production_timeline_snapshot.trace_id),
                    correlation_id = COALESCE(EXCLUDED.correlation_id, production_timeline_snapshot.correlation_id),
                    last_occurred_at = EXCLUDED.last_occurred_at,
                    updated_at = EXCLUDED.updated_at,
                    details = COALESCE(EXCLUDED.details, production_timeline_snapshot.details)
                WHERE EXCLUDED.last_occurred_at >= production_timeline_snapshot.last_occurred_at
                """,
                productionId,
                envelope.getEventId().toString(),
                envelope.getType(),
                currentStatus,
                productionName,
                actor,
                source,
                envelope.getTraceId(),
                envelope.getCorrelationId(),
                occurredAt,
                now,
                details
        );
    }

    private static String resolveProductionId(Map<String, Object> payload, String aggregateKey, String fallbackId) {
        String fromPayload = extractText(payload, "productionId");
        if (fromPayload != null) {
            return fromPayload;
        }
        return hasText(aggregateKey) ? aggregateKey.trim() : fallbackId;
    }

    private static String extractText(Map<String, Object> payload, String key) {
        if (payload == null || !payload.containsKey(key) || payload.get(key) == null) {
            return null;
        }
        String text = String.valueOf(payload.get(key)).trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
