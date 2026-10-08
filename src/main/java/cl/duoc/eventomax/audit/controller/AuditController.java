package cl.duoc.eventomax.audit.controller;

import cl.duoc.eventomax.audit.dto.AuditEventResponse;
import cl.duoc.eventomax.audit.exception.InvalidDateRangeException;
import cl.duoc.eventomax.audit.service.AuditQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/audit")
@Tag(name = "Audit", description = "Auditing API - Read Only")
public class AuditController {

    private final AuditQueryService auditQueryService;

    public AuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping("/timeline")
    @Operation(summary = "Get audit timeline", description = "Retrieves read-only audit timeline filtered by actor, date range or type")
    public ResponseEntity<List<AuditEventResponse>> getTimeline(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String type) {

        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidDateRangeException("The 'from' date cannot be after the 'to' date");
        }

        List<AuditEventResponse> timeline = auditQueryService.getTimeline(actor, from, to, type);
        return ResponseEntity.ok(timeline);
    }
}
