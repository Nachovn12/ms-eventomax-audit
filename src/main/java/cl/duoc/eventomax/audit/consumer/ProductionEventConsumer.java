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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "eventomax.audit.kafka.enabled", havingValue = "true")
public class ProductionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProductionEventConsumer.class);

    private final AuditService auditService;

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
    public void consume(ProductionEventMessage message, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        if (message == null || message.getEventId() == null || message.getEventId().isBlank()
                || message.getActor() == null || message.getActor().isBlank()
                || message.getType() == null || message.getType().isBlank() || message.getTimestamp() == null) {
            throw new IllegalArgumentException("Audit event requires eventId, actor, type and timestamp");
        }
        log.debug("Received audit event from topic {}", topic);
        
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
