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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

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

        // Convert Instant (UTC) explicitly to LocalDateTime for PostgreSQL TIMESTAMP WITHOUT TIME ZONE column
        LocalDateTime eventTimestamp = LocalDateTime.ofInstant(envelope.getTimestamp(), ZoneOffset.UTC);
        event.setTimestamp(eventTimestamp);

        // Serialize payload map to JSON string for details column
        try {
            event.setDetails(objectMapper.writeValueAsString(envelope.getPayload()));
        } catch (Exception e) {
            event.setDetails(envelope.getPayload().toString());
        }

        auditService.processEvent(event);
    }

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
            requireField(payload, "productionId", "ProductionCreated");
            requireNonBlankField(payload, "productionName", "ProductionCreated");
            requireNonBlankField(payload, "status", "ProductionCreated");
            requireField(payload, "occurredAt", "ProductionCreated");
        } else if ("ProductionStatusChanged".equals(type)) {
            requireField(payload, "productionId", "ProductionStatusChanged");
            requireNonBlankField(payload, "previousStatus", "ProductionStatusChanged");
            requireNonBlankField(payload, "newStatus", "ProductionStatusChanged");
            requireField(payload, "occurredAt", "ProductionStatusChanged");
        } else {
            throw new IllegalArgumentException("Unsupported event type: " + type);
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
