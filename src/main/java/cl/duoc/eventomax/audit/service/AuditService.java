package cl.duoc.eventomax.audit.service;

import cl.duoc.eventomax.audit.model.AuditEvent;
import cl.duoc.eventomax.audit.model.ProcessedEvent;
import cl.duoc.eventomax.audit.repository.AuditEventRepository;
import cl.duoc.eventomax.audit.repository.ProcessedEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

@Service
public class AuditService {
    
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository auditEventRepository;
    private final ProcessedEventRepository processedEventRepository;

    public AuditService(AuditEventRepository auditEventRepository, ProcessedEventRepository processedEventRepository) {
        this.auditEventRepository = auditEventRepository;
        this.processedEventRepository = processedEventRepository;
    }

    @Transactional
    public void processEvent(AuditEvent event) {
        if (processedEventRepository.existsById(event.getEventId())) {
            log.info("Event {} already processed, skipping", event.getEventId());
            return;
        }

        auditEventRepository.save(event);
        processedEventRepository.save(new ProcessedEvent(event.getEventId(), LocalDateTime.now()));
        log.info("Successfully processed and saved audit event {}", event.getEventId());
    }
}
