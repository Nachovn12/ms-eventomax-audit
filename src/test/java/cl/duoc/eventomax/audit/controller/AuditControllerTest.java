package cl.duoc.eventomax.audit.controller;

import cl.duoc.eventomax.audit.exception.GlobalExceptionHandler;
import cl.duoc.eventomax.audit.service.AuditQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class AuditControllerTest {

    @Test
    void rejectsUnboundedOrNegativePages() throws Exception {
        mockMvc.perform(get("/api/audit/timeline").param("size", "201")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/audit/timeline").param("size", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/audit/timeline").param("page", "-1")).andExpect(status().isBadRequest());
    }

    private MockMvc mockMvc;

    @Mock
    private AuditQueryService auditQueryService;

    @InjectMocks
    private AuditController auditController;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(auditController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    public void getTimeline_Returns200() throws Exception {
        when(auditQueryService.getTimeline(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/audit/timeline"))
                .andExpect(status().isOk());
    }

    @Test
    public void getTimeline_WithFilters_Returns200() throws Exception {
        when(auditQueryService.getTimeline(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/audit/timeline")
                .param("actor", "admin")
                .param("type", "LOGIN")
                .param("from", "2026-10-08T10:00:00")
                .param("to", "2026-10-08T12:00:00"))
                .andExpect(status().isOk());
    }

    @Test
    public void getTimeline_WithInvalidDateRange_Returns400() throws Exception {
        mockMvc.perform(get("/api/audit/timeline")
                .param("from", "2026-10-08T12:00:00")
                .param("to", "2026-10-08T10:00:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void getTimeline_WithInvalidDateFormat_Returns400() throws Exception {
        mockMvc.perform(get("/api/audit/timeline")
                .param("from", "invalid-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void getTimeline_ReturnsEventsWithTracing() throws Exception {
        var response = new cl.duoc.eventomax.audit.dto.AuditEventResponse(
                "event-123", "actor-1", "ProductionCreated",
                java.time.LocalDateTime.of(2026, 10, 12, 10, 0),
                "{\"foo\":\"bar\"}", "trace-abc", "corr-xyz"
        );
        when(auditQueryService.getTimeline(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(java.util.List.of(response));

        mockMvc.perform(get("/api/audit/timeline"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].eventId").value("event-123"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].traceId").value("trace-abc"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].correlationId").value("corr-xyz"));
    }

    @Test
    public void getTimeline_ReturnsEventsWithSource() throws Exception {
        var response = new cl.duoc.eventomax.audit.dto.AuditEventResponse(
                "event-123", "actor-1", "ProductionCreated",
                java.time.LocalDateTime.of(2026, 10, 12, 10, 0),
                "{\"foo\":\"bar\"}", "trace-abc", "corr-xyz", "web-ui"
        );
        when(auditQueryService.getTimeline(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(java.util.List.of(response));

        mockMvc.perform(get("/api/audit/timeline"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].source").value("web-ui"));
    }

    @Test
    public void getLatestSnapshots_Returns200AndRejectsInvalidPaging() throws Exception {
        var snapshot = new cl.duoc.eventomax.audit.dto.ProductionTimelineSnapshotResponse(
                "42", "event-42", "ProductionStatusChanged", "EN_MONTAJE",
                "Festival Primavera", "prod@duocuc.cl", "ms-eventomax-productions",
                "trace-42", "corr-42",
                java.time.LocalDateTime.of(2026, 10, 12, 11, 0),
                java.time.LocalDateTime.of(2026, 10, 12, 11, 0, 1),
                "{\"productionId\":42,\"newStatus\":\"EN_MONTAJE\"}"
        );
        when(auditQueryService.getLatestSnapshots("42", 0, 25)).thenReturn(java.util.List.of(snapshot));

        mockMvc.perform(get("/api/audit/timeline/latest")
                        .param("productionId", "42")
                        .param("page", "0")
                        .param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].productionId").value("42"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].currentStatus").value("EN_MONTAJE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].source").value("ms-eventomax-productions"));

        mockMvc.perform(get("/api/audit/timeline/latest").param("size", "201"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void getTimelineAndSnapshotsExposeOptionalActorDisplayName() throws Exception {
        var eventWithDisplayName = new cl.duoc.eventomax.audit.dto.AuditEventResponse(
                "event-with-name", "tid:oid-1", "ProductionCreated",
                java.time.LocalDateTime.of(2026, 11, 21, 10, 0),
                "{\"productionId\":9}", "trace-1", "corr-1", "ms-eventomax-productions", "Ignacio Valeria"
        );
        var legacyEventWithoutDisplayName = new cl.duoc.eventomax.audit.dto.AuditEventResponse(
                "event-legacy", "sub-legacy-123", "ProductionStatusChanged",
                java.time.LocalDateTime.of(2026, 11, 21, 9, 0),
                "{\"productionId\":9}", "trace-0", "corr-0", "ms-eventomax-productions"
        );
        when(auditQueryService.getTimeline(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(java.util.List.of(eventWithDisplayName, legacyEventWithoutDisplayName));

        mockMvc.perform(get("/api/audit/timeline"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].actor").value("tid:oid-1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].actorDisplayName").value("Ignacio Valeria"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[1].actor").value("sub-legacy-123"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[1].actorDisplayName").value((Object) null));

        var snapshotWithDisplayName = new cl.duoc.eventomax.audit.dto.ProductionTimelineSnapshotResponse(
                "9", "event-with-name", "ProductionCreated", "SOLICITADO",
                "Evento Demo", "tid:oid-1", "Ignacio Valeria", "ms-eventomax-productions",
                "trace-1", "corr-1",
                java.time.LocalDateTime.of(2026, 11, 21, 10, 0),
                java.time.LocalDateTime.of(2026, 11, 21, 10, 0, 1),
                "{\"productionId\":9}"
        );
        when(auditQueryService.getLatestSnapshots("9", 0, 50)).thenReturn(java.util.List.of(snapshotWithDisplayName));

        mockMvc.perform(get("/api/audit/timeline/latest").param("productionId", "9"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].actor").value("tid:oid-1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].actorDisplayName").value("Ignacio Valeria"));
    }
}
