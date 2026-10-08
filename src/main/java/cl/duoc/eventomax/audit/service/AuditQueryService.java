package cl.duoc.eventomax.audit.service;

import cl.duoc.eventomax.audit.dto.AuditEventResponse;
import cl.duoc.eventomax.audit.model.AuditEvent;
import cl.duoc.eventomax.audit.repository.AuditEventRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class AuditQueryService {

    private final AuditEventRepository auditEventRepository;

    public AuditQueryService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    public List<AuditEventResponse> getTimeline(String actor, LocalDateTime from, LocalDateTime to, String type) {
        Specification<AuditEvent> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            
            if (actor != null && !actor.trim().isEmpty()) {
                predicates.add(cb.equal(root.get("actor"), actor));
            }
            if (type != null && !type.trim().isEmpty()) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), to));
            }
            
            // Order by timestamp descending
            query.orderBy(cb.desc(root.get("timestamp")));
            
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return auditEventRepository.findAll(spec).stream()
                .map(event -> new AuditEventResponse(
                        event.getEventId(),
                        event.getActor(),
                        event.getType(),
                        event.getTimestamp(),
                        event.getDetails()
                ))
                .collect(Collectors.toList());
    }
}
