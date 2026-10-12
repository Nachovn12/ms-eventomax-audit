package cl.duoc.eventomax.audit;

import cl.duoc.eventomax.audit.security.AuditSecurityConfiguration;
import tools.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "eventomax.audit.kafka.enabled=true",
        "eventomax.audit.kafka.topic=productions.events.test",
        "eventomax.audit.kafka.group-id=audit-test-group",
        "eventomax.audit.kafka.auto-create-topics=true"
})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext
@Import(AuditKafkaTests.TestJwtKeys.class)
class AuditKafkaTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.4.0");

    static final RSAKey KEY = newKey();

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("ENTRA_ISSUER_URI", () -> "https://issuer.invalid/test");
        r.add("ENTRA_AUDIENCE", () -> "audit-test");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE audit_event, processed_event RESTART IDENTITY");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // Real EMX-92 ProductionCreated JSON
    private static final String REAL_PRODUCTION_CREATED_JSON = """
            {
              "schemaVersion": "1.0",
              "eventId": "260a509a-74b6-4dc4-950e-cde9af1dfafe",
              "type": "ProductionCreated",
              "timestamp": "2026-10-09T05:05:51.299570600Z",
              "traceId": null,
              "correlationId": null,
              "actor": "actor-abc",
              "payload": {
                "productionId": 1,
                "organizerId": "org-1",
                "productionName": "Test JSON and Order",
                "status": "SOLICITADO",
                "scheduledAt": "2026-10-19T02:05:51.2553441",
                "location": "Test Location",
                "occurredAt": "2026-10-09T05:05:51.299570600Z"
              }
            }
            """.strip();

    // Real EMX-92 ProductionStatusChanged JSON
    private static final String REAL_PRODUCTION_STATUS_CHANGED_JSON = """
            {
              "schemaVersion": "1.0",
              "eventId": "47609d46-b65b-495c-88cb-1f9bed8170ea",
              "type": "ProductionStatusChanged",
              "timestamp": "2026-10-09T05:05:51.409880800Z",
              "traceId": null,
              "correlationId": null,
              "actor": "actor-def",
              "payload": {
                "productionId": 1,
                "organizerId": "org-1",
                "productionName": "Test JSON and Order",
                "previousStatus": "SOLICITADO",
                "newStatus": "CONFIRMADO",
                "scheduledAt": "2026-10-19T02:05:51.255344",
                "location": "Test Location",
                "occurredAt": "2026-10-09T05:05:51.409880800Z"
              }
            }
            """.strip();

    @Test
    void consumesRealProductionCreatedEventAndPersistsToPostgres() throws Exception {
        send(REAL_PRODUCTION_CREATED_JSON);

        String eventId = "260a509a-74b6-4dc4-950e-cde9af1dfafe";
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count(eventId)).isEqualTo(1));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT event_id, actor, type, event_timestamp, details, trace_id, correlation_id FROM audit_event WHERE event_id = ?",
                eventId);

        assertThat(row.get("event_id")).isEqualTo(eventId);
        assertThat(row.get("actor")).isEqualTo("actor-abc");
        assertThat(row.get("type")).isEqualTo("ProductionCreated");
        assertThat(row.get("event_timestamp").toString()).startsWith("2026-10-09 05:05:51");
        assertThat(row.get("trace_id")).isNull();
        assertThat(row.get("correlation_id")).isNull();

        String details = (String) row.get("details");
        assertThat(details).contains("\"productionId\":1");
        assertThat(details).contains("\"productionName\":\"Test JSON and Order\"");
        assertThat(details).contains("\"status\":\"SOLICITADO\"");
        assertThat(details).contains("\"organizerId\":\"org-1\"");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId)).isEqualTo(1);
    }

    @Test
    void consumesRealProductionStatusChangedEventAndPersistsToPostgres() throws Exception {
        send(REAL_PRODUCTION_STATUS_CHANGED_JSON);

        String eventId = "47609d46-b65b-495c-88cb-1f9bed8170ea";
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count(eventId)).isEqualTo(1));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT event_id, actor, type, event_timestamp, details, trace_id, correlation_id FROM audit_event WHERE event_id = ?",
                eventId);

        assertThat(row.get("event_id")).isEqualTo(eventId);
        assertThat(row.get("actor")).isEqualTo("actor-def");
        assertThat(row.get("type")).isEqualTo("ProductionStatusChanged");
        assertThat(row.get("event_timestamp").toString()).startsWith("2026-10-09 05:05:51");
        assertThat(row.get("trace_id")).isNull();
        assertThat(row.get("correlation_id")).isNull();

        String details = (String) row.get("details");
        assertThat(details).contains("\"previousStatus\":\"SOLICITADO\"");
        assertThat(details).contains("\"newStatus\":\"CONFIRMADO\"");
        assertThat(details).contains("\"productionId\":1");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId)).isEqualTo(1);
    }

    @Test
    void consumesEventWithTraceAndCorrelationIdPersistsAndExposesViaRest() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String traceId = "test-trace-uuid-1234";
        String correlationId = "test-corr-uuid-5678";
        String json = """
            {
              "schemaVersion": "1.0",
              "eventId": "%s",
              "type": "ProductionStatusChanged",
              "timestamp": "2026-10-12T17:13:34.060102200Z",
              "traceId": "%s",
              "correlationId": "%s",
              "actor": "actor-trace-test",
              "payload": {
                "productionId": 42,
                "organizerId": "org-trace",
                "productionName": "Trace Event Test",
                "previousStatus": "SOLICITADO",
                "newStatus": "CONFIRMADO",
                "scheduledAt": "2026-10-19T02:05:51.255344",
                "location": "Trace Arena",
                "occurredAt": "2026-10-12T17:13:34.060102200Z"
              }
            }
            """.formatted(eventId, traceId, correlationId).strip();

        send(json);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count(eventId)).isEqualTo(1));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT event_id, actor, type, event_timestamp, details, trace_id, correlation_id FROM audit_event WHERE event_id = ?",
                eventId);

        assertThat(row.get("event_id")).isEqualTo(eventId);
        assertThat(row.get("actor")).isEqualTo("actor-trace-test");
        assertThat(row.get("trace_id")).isEqualTo(traceId);
        assertThat(row.get("correlation_id")).isEqualTo(correlationId);

        String token = token("Auditor", "access_as_user", "audit-test", "https://issuer.invalid/test", Instant.now().plusSeconds(3600));
        mvc.perform(get("/api/audit/timeline")
                .header("Authorization", "Bearer " + token)
                .param("actor", "actor-trace-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value(eventId))
                .andExpect(jsonPath("$[0].actor").value("actor-trace-test"))
                .andExpect(jsonPath("$[0].traceId").value(traceId))
                .andExpect(jsonPath("$[0].correlationId").value(correlationId));
    }

    @Test
    void historicalEventsWithNullTraceIdsExposeNullViaRest() throws Exception {
        String eventId = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO audit_event (event_id, actor, type, event_timestamp, details, trace_id, correlation_id)
            VALUES (?, 'actor-historical', 'ProductionCreated', '2026-10-01 10:00:00', '{"historical":true}', null, null)
            """, eventId);
        jdbc.update("INSERT INTO processed_event (event_id, processed_at) VALUES (?, '2026-10-01 10:00:00')", eventId);

        String token = token("Auditor", "access_as_user", "audit-test", "https://issuer.invalid/test", Instant.now().plusSeconds(3600));
        mvc.perform(get("/api/audit/timeline")
                .header("Authorization", "Bearer " + token)
                .param("actor", "actor-historical"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value(eventId))
                .andExpect(jsonPath("$[0].traceId").value((Object) null))
                .andExpect(jsonPath("$[0].correlationId").value((Object) null));
    }

    @Test
    void flywayAppliedAllMigrationsThroughV4() {
        List<String> versions = jdbc.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class);
        assertThat(versions).contains("1", "2", "3", "4");
    }

    @Test
    void idempotencyDuplicateRealEventConsumedOnlyOnce() throws Exception {
        String eventId = "260a509a-74b6-4dc4-950e-cde9af1dfafe";
        send(REAL_PRODUCTION_CREATED_JSON);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count(eventId)).isEqualTo(1));

        // Send duplicate
        send(REAL_PRODUCTION_CREATED_JSON);

        // Send barrier record with another ID on same partition
        String barrierId = UUID.randomUUID().toString();
        String barrierJson = REAL_PRODUCTION_CREATED_JSON.replace(eventId, barrierId);
        send(barrierJson);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count(barrierId)).isEqualTo(1));

        // Duplicate was safely ignored, exactly 1 persisted
        assertThat(count(eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId)).isEqualTo(1);
    }

    @Test
    void retriesTransientDatabaseFailureThenPersistsOnce() throws Exception {
        String transientId = UUID.randomUUID().toString();
        jdbc.execute("CREATE SEQUENCE retry_attempt START 1");
        jdbc.execute(String.format("""
            CREATE FUNCTION fail_first_two() RETURNS trigger AS $$
            BEGIN
                IF NEW.event_id = '%s' AND nextval('retry_attempt') <= 2 THEN
                    RAISE EXCEPTION 'temporary test failure';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """, transientId));
        jdbc.execute("CREATE TRIGGER transient_failure BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION fail_first_two()");
        try {
            String transientJson = REAL_PRODUCTION_CREATED_JSON.replace("260a509a-74b6-4dc4-950e-cde9af1dfafe", transientId);
            send(transientJson);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count(transientId)).isEqualTo(1));
            assertThat(jdbc.queryForObject("SELECT last_value FROM retry_attempt", Long.class)).isEqualTo(3L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, transientId)).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER transient_failure ON audit_event");
            jdbc.execute("DROP FUNCTION fail_first_two()");
            jdbc.execute("DROP SEQUENCE retry_attempt");
        }
    }

    @Test
    void missingSchemaVersionReachesDltWithoutPersisting() throws Exception {
        String id = UUID.randomUUID().toString();
        String missingSchema = REAL_PRODUCTION_CREATED_JSON.replace("260a509a-74b6-4dc4-950e-cde9af1dfafe", id)
                .replace("\"schemaVersion\": \"1.0\",\n", "");
        assertDlt(missingSchema, false);
        assertThat(count(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void invalidSchemaVersionReachesDltWithoutPersisting() throws Exception {
        String id = UUID.randomUUID().toString();
        String invalidSchema = REAL_PRODUCTION_CREATED_JSON.replace("260a509a-74b6-4dc4-950e-cde9af1dfafe", id)
                .replace("\"schemaVersion\": \"1.0\"", "\"schemaVersion\": \"2.0\"");
        assertDlt(invalidSchema, false);
        assertThat(count(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void missingMandatoryFieldReachesDltWithoutPersisting() throws Exception {
        String id = UUID.randomUUID().toString();
        // Missing actor
        String missingActor = REAL_PRODUCTION_CREATED_JSON.replace("260a509a-74b6-4dc4-950e-cde9af1dfafe", id)
                .replace("\"actor\": \"actor-abc\",", "\"actor\": \"\",");
        assertDlt(missingActor, false);
        assertThat(count(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void invalidProductionIdReachesDltWithoutPersisting() throws Exception {
        for (String invalidId : List.of("-5", "0", "1.5", "\"abc\"", "9223372036854775808")) {
            String id = UUID.randomUUID().toString();
            String invalidIdJson = REAL_PRODUCTION_CREATED_JSON.replace("260a509a-74b6-4dc4-950e-cde9af1dfafe", id)
                    .replace("\"productionId\": 1,", "\"productionId\": " + invalidId + ",");
            assertDlt(invalidIdJson, false);
            assertThat(count(id)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, id)).isZero();
        }
    }

    @Test
    void productionIdWithinLongRangeIsPersisted() throws Exception {
        String id = UUID.randomUUID().toString();
        String validMaxLongJson = REAL_PRODUCTION_CREATED_JSON.replace("260a509a-74b6-4dc4-950e-cde9af1dfafe", id)
                .replace("\"productionId\": 1,", "\"productionId\": 9223372036854775807,");
        send(validMaxLongJson);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count(id)).isEqualTo(1));
    }

    @Test
    void validLifecycleTransitionsArePersistedOnceAndNeverReachDlt() throws Exception {
        // Lifecycle transitions in ProductionStatus domain:
        // 1. SOLICITADO -> CONFIRMADO
        // 2. CONFIRMADO -> EN_MONTAJE
        // 3. EN_MONTAJE -> EN_EJECUCION
        // 4. EN_EJECUCION -> CERRADO
        // 5. CONFIRMADO -> CANCELADO
        List<String[]> transitions = List.of(
                new String[]{"SOLICITADO", "CONFIRMADO"},
                new String[]{"CONFIRMADO", "EN_MONTAJE"},
                new String[]{"EN_MONTAJE", "EN_EJECUCION"},
                new String[]{"EN_EJECUCION", "CERRADO"},
                new String[]{"CONFIRMADO", "CANCELADO"}
        );

        List<String> eventIds = new ArrayList<>();
        for (String[] pair : transitions) {
            String eventId = UUID.randomUUID().toString();
            eventIds.add(eventId);
            String json = statusChangedJson(eventId, 1L, pair[0], pair[1]);
            send(json);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count(eventId)).isEqualTo(1));

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT event_id, actor, type, details FROM audit_event WHERE event_id = ?", eventId);
            assertThat(row.get("type")).isEqualTo("ProductionStatusChanged");
            String details = (String) row.get("details");
            assertThat(details).contains("\"previousStatus\":\"" + pair[0] + "\"");
            assertThat(details).contains("\"newStatus\":\"" + pair[1] + "\"");

            // Persisted exactly once in processed_event
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId)).isEqualTo(1);
        }

        verifyNotInDlt(eventIds);
    }

    @Test
    void nonDomainStatusesEnProcesoFinalizadoAndPublicadoReachDltWithoutPersisting() throws Exception {
        for (String invalidStatus : List.of("EN_PROCESO", "FINALIZADO", "PUBLICADO")) {
            String id = UUID.randomUUID().toString();
            String invalidJson = statusChangedJson(id, 1L, "SOLICITADO", invalidStatus);
            assertDlt(invalidJson, false);
            assertThat(count(id)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, id)).isZero();
        }
    }

    @Test
    void productionCreatedWithNonSolicitadoStatusReachesDltWithoutPersisting() throws Exception {
        String id = UUID.randomUUID().toString();
        // ProductionCreated must have status SOLICITADO; CONFIRMADO is rejected on creation
        String invalidCreated = REAL_PRODUCTION_CREATED_JSON.replace("260a509a-74b6-4dc4-950e-cde9af1dfafe", id)
                .replace("\"status\": \"SOLICITADO\",", "\"status\": \"CONFIRMADO\",");
        assertDlt(invalidCreated, false);
        assertThat(count(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void invalidOccurredAtReachesDltWithoutPersisting() throws Exception {
        String id = UUID.randomUUID().toString();
        String invalidOccurredAtJson = REAL_PRODUCTION_CREATED_JSON.replace("260a509a-74b6-4dc4-950e-cde9af1dfafe", id)
                .replace("\"occurredAt\": \"2026-10-09T05:05:51.299570600Z\"", "\"occurredAt\": \"not-a-valid-timestamp\"");
        assertDlt(invalidOccurredAtJson, false);
        assertThat(count(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void malformedJsonReachesDltAsOriginalBytesAndDoesNotBlockNextRecord() throws Exception {
        assertDlt("{malformed-poison-pill-" + UUID.randomUUID(), true);
        String nextId = UUID.randomUUID().toString();
        String validJson = REAL_PRODUCTION_CREATED_JSON.replace("260a509a-74b6-4dc4-950e-cde9af1dfafe", nextId);
        send(validJson);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count(nextId)).isEqualTo(1));
    }

    @Test
    void endToEndFlowFromKafkaToPostgresToRestTimeline() throws Exception {
        send(REAL_PRODUCTION_CREATED_JSON);
        String eventId = "260a509a-74b6-4dc4-950e-cde9af1dfafe";
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count(eventId)).isEqualTo(1));

        String jwt = token("Auditor", "access_as_user", "audit-test", "https://issuer.invalid/test", Instant.now().plusSeconds(300));

        mvc.perform(get("/api/audit/timeline")
                        .header("Authorization", "Bearer " + jwt)
                        .param("actor", "actor-abc")
                        .param("type", "ProductionCreated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventId").value(eventId))
                .andExpect(jsonPath("$[0].actor").value("actor-abc"))
                .andExpect(jsonPath("$[0].type").value("ProductionCreated"))
                .andExpect(jsonPath("$[0].details").value(org.hamcrest.Matchers.containsString("Test JSON and Order")));
    }

    private void assertDlt(String payload, boolean raw) throws Exception {
        Map<String,Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "inspect-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (var consumer = new KafkaConsumer<String, byte[]>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of("productions.events.test-audit-dlt"));
            send(payload);
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    String received = new String(record.value(), StandardCharsets.UTF_8);
                    if (raw && payload.equals(received)) return true;
                    if (!raw && received.startsWith("{\"")) {
                        var pTree = mapper.readTree(payload);
                        var rTree = mapper.readTree(received);
                        if (pTree.equals(rTree)) return true;
                        if (!pTree.has("schemaVersion") && rTree.has("schemaVersion") && rTree.get("schemaVersion").isNull()) {
                            if (rTree instanceof tools.jackson.databind.node.ObjectNode obj) {
                                obj.remove("schemaVersion");
                                if (pTree.equals(obj)) return true;
                            }
                        }
                    }
                }
                return false;
            });
        }
    }

    private int count(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_id = ?", Integer.class, id);
    }

    private void send(String json) throws Exception {
        var props = Map.<String,Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all");
        try (var producer = new KafkaProducer<String,String>(props, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>("productions.events.test", "1", json)).get(10, TimeUnit.SECONDS);
        }
    }

    private static String statusChangedJson(String eventId, long productionId, String prevStatus, String newStatus) {
        return """
            {
              "schemaVersion": "1.0",
              "eventId": "%s",
              "type": "ProductionStatusChanged",
              "timestamp": "2026-10-09T05:05:51.409880800Z",
              "traceId": null,
              "correlationId": null,
              "actor": "actor-lifecycle",
              "payload": {
                "productionId": %d,
                "organizerId": "org-1",
                "productionName": "Lifecycle Event",
                "previousStatus": "%s",
                "newStatus": "%s",
                "scheduledAt": "2026-10-19T02:05:51.255344",
                "location": "Main Stage",
                "occurredAt": "2026-10-09T05:05:51.409880800Z"
              }
            }
            """.formatted(eventId, productionId, prevStatus, newStatus).strip();
    }

    private void verifyNotInDlt(List<String> eventIds) {
        Map<String,Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "verify-no-dlt-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (var consumer = new KafkaConsumer<String, byte[]>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of("productions.events.test-audit-dlt"));
            var records = consumer.poll(Duration.ofMillis(1000));
            for (var record : records) {
                String payload = new String(record.value(), StandardCharsets.UTF_8);
                for (String id : eventIds) {
                    assertThat(payload).doesNotContain(id);
                }
            }
        }
    }

    static RSAKey newKey() {
        try { return new RSAKeyGenerator(2048).generate(); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String token(String role, String scope, String audience, String issuer, Instant expires) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject("test-user").issuer(issuer).audience(audience)
                .claim("roles", List.of(role)).claim("scp", scope);
        if (expires != null) claims.expirationTime(Date.from(expires));
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(KEY)); return jwt.serialize();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJwtKeys {
        @Bean @Primary
        JwtDecoder localTestDecoder() throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(AuditSecurityConfiguration.tokenValidator("https://issuer.invalid/test", "audit-test"));
            return decoder;
        }
    }
}
