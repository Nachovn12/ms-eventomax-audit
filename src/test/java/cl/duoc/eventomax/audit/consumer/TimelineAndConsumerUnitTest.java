package cl.duoc.eventomax.audit.consumer;

import cl.duoc.eventomax.audit.dto.KafkaEventEnvelope;
import cl.duoc.eventomax.audit.model.AuditEvent;
import cl.duoc.eventomax.audit.service.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TimelineAndConsumerUnitTest {

    @Mock
    private AuditService auditService;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void timelineConsumerProjectsSnapshotWhenEnvelopePresent() {
        var consumer = new TimelineEventConsumer(auditService);
        var envelope = new KafkaEventEnvelope(
                "1.0",
                UUID.randomUUID(),
                "ProductionStatusChanged",
                Instant.parse("2026-11-14T15:00:00Z"),
                "trace-1",
                "corr-1",
                "prod@duocuc.cl",
                Map.of("productionId", 77, "newStatus", "CONFIRMADO")
        );
        envelope.setSource("ms-eventomax-productions");

        consumer.consumeTimeline(envelope, "audit.timeline", "77");

        verify(auditService).projectTimelineSnapshot(envelope, "77");
    }

    @Test
    void timelineConsumerIgnoresNullEnvelope() {
        var consumer = new TimelineEventConsumer(auditService);
        consumer.consumeTimeline(null, "audit.timeline", "77");
        verifyNoInteractions(auditService);
    }

    @Test
    void productionConsumerPopulatesSourceBeforeForwardingToTimelineTopic() {
        var consumer = new ProductionEventConsumer(auditService);
        ReflectionTestUtils.setField(consumer, "kafkaTemplate", kafkaTemplate);
        ReflectionTestUtils.setField(consumer, "timelineTopic", "audit.timeline");

        var envelope = new KafkaEventEnvelope(
                "1.0",
                UUID.randomUUID(),
                "ProductionCreated",
                Instant.parse("2026-11-14T15:05:00Z"),
                "trace-9",
                "corr-9",
                "org@duocuc.cl",
                Map.of(
                        "productionId", 88,
                        "organizerId", "org-1",
                        "productionName", "Gala",
                        "status", "SOLICITADO",
                        "scheduledAt", "2026-11-20T18:00:00",
                        "location", "Teatro Municipal",
                        "occurredAt", "2026-11-14T15:05:00Z"
                )
        );

        consumer.consume(envelope, "productions.events");

        ArgumentCaptor<AuditEvent> savedCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).processEvent(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getSource()).isEqualTo("ms-eventomax-productions");

        ArgumentCaptor<KafkaEventEnvelope> forwardedCaptor = ArgumentCaptor.forClass(KafkaEventEnvelope.class);
        verify(kafkaTemplate).send(eq("audit.timeline"), eq("88"), forwardedCaptor.capture());
        assertThat(forwardedCaptor.getValue().getSource()).isEqualTo("ms-eventomax-productions");
    }
}
