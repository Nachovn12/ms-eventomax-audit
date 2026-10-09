package cl.duoc.eventomax.audit.service;

import cl.duoc.eventomax.audit.model.AuditEvent;
import cl.duoc.eventomax.audit.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
public class AuditService {
    
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository auditEventRepository;
    private final JdbcTemplate jdbc;

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
}
