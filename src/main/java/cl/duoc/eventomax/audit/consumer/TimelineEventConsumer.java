package cl.duoc.eventomax.audit.consumer;

import cl.duoc.eventomax.audit.dto.KafkaEventEnvelope;
import cl.duoc.eventomax.audit.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumidor del tópico compactado {@code audit.timeline} que mantiene la proyección
 * de último estado conocido por producción ({@code production_timeline_snapshot}).
 */
@Component
@ConditionalOnProperty(name = "eventomax.audit.kafka.enabled", havingValue = "true")
public class TimelineEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TimelineEventConsumer.class);

    private final AuditService auditService;

    public TimelineEventConsumer(AuditService auditService) {
        this.auditService = auditService;
    }

    @KafkaListener(
            topics = "${eventomax.audit.kafka.timeline-topic:${eventomax.audit.kafka.topic.timeline:audit.timeline}}",
            groupId = "${eventomax.audit.kafka.timeline-group-id:audit-timeline-group}"
    )
    public void consumeTimeline(
            KafkaEventEnvelope envelope,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String key) {
        if (envelope == null) {
            log.warn("Received null payload from timeline topic: {}", topic);
            return;
        }
        auditService.projectTimelineSnapshot(envelope, key);
        log.info("Timeline snapshot projected from Kafka topic [{}], key [{}]: eventId={}, actor={}, type={}, source={}, timestamp={}",
                topic, key, envelope.getEventId(), envelope.getActor(), envelope.getType(), envelope.getSource(), envelope.getTimestamp());
    }
}
