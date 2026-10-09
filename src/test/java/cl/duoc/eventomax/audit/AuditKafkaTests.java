package cl.duoc.eventomax.audit;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Transport tests use the provisional DTO ONLY on an isolated topic, not the EMX-71 contract. */
@SpringBootTest(properties = {
        "eventomax.audit.kafka.enabled=true",
        "eventomax.audit.kafka.topic=audit.test",
        "eventomax.audit.kafka.group-id=audit-test",
        "eventomax.audit.kafka.auto-create-topics=true"
})
@ActiveProfiles("test")
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = "audit.test", bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class AuditKafkaTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired JdbcTemplate jdbc;

    @Test
    void consumesHeaderlessJsonAndDuplicateOnlyOnce() throws Exception {
        String id = "delivery-" + UUID.randomUUID();
        send(json(id, "alice"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count(id)).isEqualTo(1));
        send(json(id, "alice"));
        // A later record on the same partition is a processing barrier for the duplicate.
        String barrier = "barrier-" + UUID.randomUUID();
        send(json(barrier, "alice"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count(barrier)).isEqualTo(1));
        assertThat(count(id)).isEqualTo(1);
    }

    @Test
    void retriesTransientDatabaseFailureThenPersistsOnce() throws Exception {
        jdbc.execute("CREATE SEQUENCE retry_attempt START 1");
        jdbc.execute("""
            CREATE FUNCTION fail_first_two() RETURNS trigger AS $$
            BEGIN
                IF NEW.event_id = 'transient' AND nextval('retry_attempt') <= 2 THEN
                    RAISE EXCEPTION 'temporary test failure';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        jdbc.execute("CREATE TRIGGER transient_failure BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION fail_first_two()");
        try {
            send(json("transient", "alice"));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count("transient")).isEqualTo(1));
            assertThat(jdbc.queryForObject("SELECT last_value FROM retry_attempt", Long.class)).isEqualTo(3L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = 'transient'", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER transient_failure ON audit_event");
            jdbc.execute("DROP FUNCTION fail_first_two()");
            jdbc.execute("DROP SEQUENCE retry_attempt");
        }
    }

    @Test
    void permanentFailureReachesDltWithoutPersisting() throws Exception {
        String id = "invalid-" + UUID.randomUUID();
        String payload = json(id, "");
        assertDlt(payload, false);
        assertThat(count(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void malformedJsonReachesDltAsOriginalBytesAndDoesNotBlockNextRecord() throws Exception {
        assertDlt("{malformed-" + UUID.randomUUID(), true);
        String id = "after-poison-" + UUID.randomUUID();
        send(json(id, "alice"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count(id)).isEqualTo(1));
    }

    private void assertDlt(String payload, boolean raw) throws Exception {
        Map<String,Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "inspect-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (var consumer = new KafkaConsumer<String, byte[]>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of("audit.test-audit-dlt"));
            send(payload);
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    String received = new String(record.value(), StandardCharsets.UTF_8);
                    if (raw && payload.equals(received)) return true;
                    if (!raw && received.startsWith("{\"")) {
                        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
                        if (mapper.readTree(payload).equals(mapper.readTree(received))) return true;
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
        var props = Map.<String,Object>of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.ACKS_CONFIG, "all");
        try (var producer = new KafkaProducer<String,String>(props, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>("audit.test", "same-partition", json)).get(10, TimeUnit.SECONDS);
        }
    }

    private static String json(String id, String actor) {
        return """
                {"eventId":"%s","actor":"%s","type":"STATUS","timestamp":"2026-10-08T10:00:00","details":"test fixture"}
                """.formatted(id, actor).strip();
    }
}
