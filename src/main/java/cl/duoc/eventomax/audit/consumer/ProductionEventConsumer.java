package cl.duoc.eventomax.audit.consumer;

import cl.duoc.eventomax.audit.dto.KafkaEventEnvelope;
import cl.duoc.eventomax.audit.model.AuditEvent;
import cl.duoc.eventomax.audit.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "eventomax.audit.kafka.enabled", havingValue = "true")
public class ProductionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProductionEventConsumer.class);

    private final AuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProductionEventConsumer(AuditService auditService) {
        this.auditService = auditService;
    }

    @RetryableTopic(
            attempts = "3",
            kafkaTemplate = "auditRetryKafkaTemplate",
            retryTopicSuffix = "-audit-retry",
            dltTopicSuffix = "-audit-dlt",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoCreateTopics = "${eventomax.audit.kafka.auto-create-topics:false}",
            autoStartDltHandler = "false"
    )
    @KafkaListener(topics = "${eventomax.audit.kafka.topic}", groupId = "${eventomax.audit.kafka.group-id}")
    public void consume(KafkaEventEnvelope envelope, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        validateEnvelope(envelope);
        validatePayload(envelope);

        log.debug("Received audit event id {} type {} from topic {}", envelope.getEventId(), envelope.getType(), topic);

        AuditEvent event = new AuditEvent();
        event.setEventId(envelope.getEventId().toString());
        event.setActor(envelope.getActor());
        event.setType(envelope.getType());
        event.setTraceId(envelope.getTraceId());
        event.setCorrelationId(envelope.getCorrelationId());

        // Convert Instant (UTC) explicitly to LocalDateTime for PostgreSQL TIMESTAMP WITHOUT TIME ZONE column
        LocalDateTime eventTimestamp = LocalDateTime.ofInstant(envelope.getTimestamp(), ZoneOffset.UTC);
        event.setTimestamp(eventTimestamp);

        // Serialize payload map to JSON string for details column
        try {
            event.setDetails(objectMapper.writeValueAsString(envelope.getPayload()));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize audit event payload to valid JSON", e);
        }

        auditService.processEvent(event);
    }

    private static final Set<String> VALID_PRODUCTION_STATUSES = Set.of(
            "SOLICITADO", "CONFIRMADO", "EN_MONTAJE", "EN_EJECUCION", "CERRADO", "CANCELADO"
    );

    private void validateEnvelope(KafkaEventEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("Audit event envelope is null");
        }
        if (envelope.getSchemaVersion() == null || !envelope.getSchemaVersion().equals("1.0")) {
            throw new IllegalArgumentException("Invalid schemaVersion: expected 1.0, received " + envelope.getSchemaVersion());
        }
        if (envelope.getEventId() == null) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (envelope.getType() == null || envelope.getType().isBlank()) {
            throw new IllegalArgumentException("type is required");
        }
        if (envelope.getTimestamp() == null) {
            throw new IllegalArgumentException("timestamp is required");
        }
        if (envelope.getActor() == null || envelope.getActor().isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
        if (envelope.getPayload() == null || envelope.getPayload().isEmpty()) {
            throw new IllegalArgumentException("payload is required");
        }
    }

    private void validatePayload(KafkaEventEnvelope envelope) {
        String type = envelope.getType();
        Map<String, Object> payload = envelope.getPayload();

        if ("ProductionCreated".equals(type)) {
            validateProductionId(payload, "ProductionCreated");
            requireNonBlankField(payload, "productionName", "ProductionCreated");
            validateCreationStatus(payload, "ProductionCreated");
            validateOccurredAt(payload, "ProductionCreated");
        } else if ("ProductionStatusChanged".equals(type)) {
            validateProductionId(payload, "ProductionStatusChanged");
            validateStatus(payload, "previousStatus", "ProductionStatusChanged");
            validateStatus(payload, "newStatus", "ProductionStatusChanged");
            validateOccurredAt(payload, "ProductionStatusChanged");
        } else {
            throw new IllegalArgumentException("Unsupported event type: " + type);
        }
    }

    private void validateProductionId(Map<String, Object> payload, String eventType) {
        requireField(payload, "productionId", eventType);
        Object raw = payload.get("productionId");
        if (raw instanceof Integer i) {
            if (i <= 0) {
                throw new IllegalArgumentException("productionId must be a positive integer in " + eventType + ", received: " + i);
            }
        } else if (raw instanceof Long l) {
            if (l <= 0) {
                throw new IllegalArgumentException("productionId must be a positive integer in " + eventType + ", received: " + l);
            }
        } else if (raw instanceof Short s) {
            if (s <= 0) {
                throw new IllegalArgumentException("productionId must be a positive integer in " + eventType + ", received: " + s);
            }
        } else if (raw instanceof Byte b) {
            if (b <= 0) {
                throw new IllegalArgumentException("productionId must be a positive integer in " + eventType + ", received: " + b);
            }
        } else if (raw instanceof java.math.BigInteger bi) {
            if (bi.compareTo(java.math.BigInteger.ZERO) <= 0 || bi.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                throw new IllegalArgumentException("productionId must be a positive integer within Long range in " + eventType + ", received: " + bi);
            }
        } else {
            throw new IllegalArgumentException("productionId must be an exact positive integer in " + eventType + ", received incompatible type or value: " + raw);
        }
    }

    private void validateCreationStatus(Map<String, Object> payload, String eventType) {
        requireNonBlankField(payload, "status", eventType);
        String status = payload.get("status").toString().trim();
        if (!"SOLICITADO".equals(status)) {
            throw new IllegalArgumentException("Expected status SOLICITADO for ProductionCreated, received '" + status + "'");
        }
    }

    private void validateStatus(Map<String, Object> payload, String field, String eventType) {
        requireNonBlankField(payload, field, eventType);
        String status = payload.get(field).toString().trim();
        if (!VALID_PRODUCTION_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Invalid production status '" + status + "' in " + eventType);
        }
    }

    private void validateOccurredAt(Map<String, Object> payload, String eventType) {
        requireField(payload, "occurredAt", eventType);
        Object raw = payload.get("occurredAt");
        if (raw instanceof Instant) {
            return;
        }
        try {
            Instant.parse(raw.toString().trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("occurredAt must be a valid ISO-8601 UTC timestamp in " + eventType, e);
        }
    }

    private void requireField(Map<String, Object> payload, String field, String eventType) {
        if (!payload.containsKey(field) || payload.get(field) == null) {
            throw new IllegalArgumentException(field + " is required for " + eventType);
        }
    }

    private void requireNonBlankField(Map<String, Object> payload, String field, String eventType) {
        requireField(payload, field, eventType);
        if (payload.get(field).toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required for " + eventType);
        }
    }

    @DltHandler
    public void handleDlt(KafkaEventEnvelope envelope, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        String eventId = envelope != null && envelope.getEventId() != null ? envelope.getEventId().toString() : "unknown";
        log.error("Event {} sent to DLT from topic {}. Manual intervention may be required.", eventId, topic);
    }
}
