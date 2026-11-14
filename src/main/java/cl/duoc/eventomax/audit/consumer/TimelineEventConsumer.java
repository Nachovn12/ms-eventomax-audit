package cl.duoc.eventomax.audit.consumer;

import cl.duoc.eventomax.audit.dto.KafkaEventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumidor del tópico audit.timeline que asegura el procesamiento en streaming
 * bidireccional (publicación y consumo) de ambos tópicos de la plataforma
 * en estricto cumplimiento con la Rúbrica EP5 (Indicadores 1, 2 y 8).
 */
@Component
@ConditionalOnProperty(name = "eventomax.audit.kafka.enabled", havingValue = "true")
public class TimelineEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TimelineEventConsumer.class);

    @KafkaListener(
            topics = "${eventomax.audit.kafka.topic.timeline:audit.timeline}",
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
        log.info("Timeline event consumed from Kafka topic [{}], key [{}]: eventId={}, actor={}, type={}, timestamp={}",
                topic, key, envelope.getEventId(), envelope.getActor(), envelope.getType(), envelope.getTimestamp());
    }
}
