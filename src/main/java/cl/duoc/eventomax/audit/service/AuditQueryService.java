package cl.duoc.eventomax.audit.service;

import cl.duoc.eventomax.audit.dto.AuditEventResponse;
import cl.duoc.eventomax.audit.dto.ProductionTimelineSnapshotResponse;
import cl.duoc.eventomax.audit.model.AuditEvent;
import cl.duoc.eventomax.audit.repository.AuditEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class AuditQueryService {

    private final AuditEventRepository auditEventRepository;
    private final JdbcTemplate jdbc;

    public AuditQueryService(AuditEventRepository auditEventRepository) {
        this(auditEventRepository, null);
    }

    @Autowired
    public AuditQueryService(AuditEventRepository auditEventRepository, JdbcTemplate jdbc) {
        this.auditEventRepository = auditEventRepository;
        this.jdbc = jdbc;
    }

    public List<AuditEventResponse> getTimeline(String actor, LocalDateTime from, LocalDateTime to, String type,
                                               int page, int size) {
        if (page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("page must be >= 0 and size between 1 and 200");
        }
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
            
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return auditEventRepository.findAll(spec,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp", "id"))).stream()
                .map(event -> new AuditEventResponse(
                        event.getEventId(),
                        event.getActor(),
                        event.getType(),
                        event.getTimestamp(),
                        event.getDetails(),
                        event.getTraceId(),
                        event.getCorrelationId(),
                        event.getSource()
                ))
                .collect(Collectors.toList());
    }

    public List<ProductionTimelineSnapshotResponse> getLatestSnapshots(String productionId, int page, int size) {
        if (page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("page must be >= 0 and size between 1 and 200");
        }
        if (jdbc == null) {
            return List.of();
        }
        int offset = page * size;
        boolean filterProduction = productionId != null && !productionId.isBlank();
        String sql = filterProduction
                ? """
                  SELECT production_id, last_event_id, last_event_type, current_status,
                         production_name, actor, source, trace_id, correlation_id,
                         last_occurred_at, updated_at, details
                  FROM production_timeline_snapshot
                  WHERE production_id = ?
                  ORDER BY last_occurred_at DESC
                  LIMIT ? OFFSET ?
                  """
                : """
                  SELECT production_id, last_event_id, last_event_type, current_status,
                         production_name, actor, source, trace_id, correlation_id,
                         last_occurred_at, updated_at, details
                  FROM production_timeline_snapshot
                  ORDER BY last_occurred_at DESC
                  LIMIT ? OFFSET ?
                  """;

        Object[] params = filterProduction
                ? new Object[]{productionId.trim(), size, offset}
                : new Object[]{size, offset};

        return jdbc.query(sql, (rs, rowNum) -> new ProductionTimelineSnapshotResponse(
                rs.getString("production_id"),
                rs.getString("last_event_id"),
                rs.getString("last_event_type"),
                rs.getString("current_status"),
                rs.getString("production_name"),
                rs.getString("actor"),
                rs.getString("source"),
                rs.getString("trace_id"),
                rs.getString("correlation_id"),
                toLocalDateTime(rs.getTimestamp("last_occurred_at")),
                toLocalDateTime(rs.getTimestamp("updated_at")),
                rs.getString("details")
        ), params);
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp != null ? timestamp.toLocalDateTime() : null;
    }
}
