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
}
