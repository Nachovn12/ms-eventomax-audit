package cl.duoc.eventomax.audit.consumer;

import cl.duoc.eventomax.audit.dto.ProductionEventMessage;
import cl.duoc.eventomax.audit.model.AuditEvent;
import cl.duoc.eventomax.audit.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class ProductionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProductionEventConsumer.class);

    private final AuditService auditService;

    public ProductionEventConsumer(AuditService auditService) {
        this.auditService = auditService;
    }

    @RetryableTopic(
            attempts = "3",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoCreateTopics = "false"
    )
    @KafkaListener(topics = "productions.events", groupId = "audit-group")
    public void consume(ProductionEventMessage message, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.info("Received event {} from topic {}", message.getEventId(), topic);
        
        AuditEvent event = new AuditEvent();
        event.setEventId(message.getEventId());
        event.setActor(message.getActor());
        event.setType(message.getType());
        event.setTimestamp(message.getTimestamp());
        event.setDetails(message.getDetails());

        auditService.processEvent(event);
    }

    @DltHandler
    public void handleDlt(ProductionEventMessage message, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("Event {} sent to DLT from topic {}. Manual intervention may be required.", message.getEventId(), topic);
    }
}
