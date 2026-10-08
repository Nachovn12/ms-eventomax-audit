package cl.duoc.eventomax.audit;

import cl.duoc.eventomax.audit.model.AuditEvent;
import cl.duoc.eventomax.audit.repository.AuditEventRepository;
import cl.duoc.eventomax.audit.service.AuditService;
import cl.duoc.eventomax.audit.security.AuditSecurityConfiguration;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(AuditApplicationTests.Keys.class)
class AuditApplicationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    static final RSAKey KEY = newKey();
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired AuditService service;
    @Autowired AuditEventRepository events;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE audit_event, processed_event RESTART IDENTITY");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void migratesAndPersistsSequentialDuplicateOnce() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND version IN ('1','2')", Integer.class)).isEqualTo(2);
        service.processEvent(event("same", "alice", "STATUS", LocalDateTime.of(2026,10,8,10,0)));
        service.processEvent(event("same", "alice", "STATUS", LocalDateTime.of(2026,10,8,10,0)));
        assertThat(events.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicatesHaveOneTimelineRecord() throws Exception {
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<?>>();
            for (int i=0;i<8;i++) results.add(executor.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                service.processEvent(event("race", "alice", "STATUS", LocalDateTime.now()));
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var result : results) result.get(20, TimeUnit.SECONDS);
        }
        assertThat(events.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void failureRollsBackClaimAndAllowsRedelivery() {
        assertThatThrownBy(() -> service.processEvent(event("rollback", null, "STATUS", LocalDateTime.now()))).isInstanceOf(RuntimeException.class);
        assertThat(events.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event", Integer.class)).isZero();
        service.processEvent(event("rollback", "alice", "STATUS", LocalDateTime.now()));
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void filtersAndPagesRealPersistedEvents() throws Exception {
        var time = LocalDateTime.of(2026,10,8,10,0);
        service.processEvent(event("first", "alice", "STATUS", time));
        service.processEvent(event("second", "alice", "STATUS", time));
        service.processEvent(event("other-actor", "bob", "STATUS", time));
        service.processEvent(event("other-type", "alice", "OTHER", time));
        service.processEvent(event("outside", "alice", "STATUS", time.plusHours(1)));
        var jwt = token("Auditor", "access_as_user", "audit-test", "https://issuer.invalid/test", Instant.now().plusSeconds(300));
        mvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer " + jwt)
                        .param("actor", "alice").param("type", "STATUS")
                        .param("from", time.toString()).param("to", time.toString()).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventId").value("second"));
        mvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer " + jwt)
                        .param("actor", "alice").param("type", "STATUS")
                        .param("from", time.toString()).param("to", time.toString()).param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$[0].eventId").value("first"));
    }

    @Test
    void requiresSignedValidTokenRoleAndScopeAndDeniesWrites() throws Exception {
        mvc.perform(get("/api/audit/timeline")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        for (String role : List.of("Admin", "Auditor")) {
            String jwt = token(role, "access_as_user", "audit-test", "https://issuer.invalid/test", Instant.now().plusSeconds(300));
            mvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer " + jwt)).andExpect(status().isOk());
            mvc.perform(post("/api/audit/timeline").header("Authorization", "Bearer " + jwt)).andExpect(status().isForbidden());
        }
        for (String jwt : List.of(
                token("Productor", "access_as_user", "audit-test", "https://issuer.invalid/test", Instant.now().plusSeconds(300)),
                token("Admin", "other", "audit-test", "https://issuer.invalid/test", Instant.now().plusSeconds(300)))) {
            mvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer " + jwt)).andExpect(status().isForbidden());
        }
    }

    @Test
    void rejectsWrongAudienceIssuerExpiredOrMissingExpiry() throws Exception {
        for (String jwt : List.of(
                token("Admin", "access_as_user", "wrong", "https://issuer.invalid/test", Instant.now().plusSeconds(300)),
                token("Admin", "access_as_user", "audit-test", "https://wrong.invalid", Instant.now().plusSeconds(300)),
                token("Admin", "access_as_user", "audit-test", "https://issuer.invalid/test", Instant.now().minusSeconds(300)),
                token("Admin", "access_as_user", "audit-test", "https://issuer.invalid/test", null))) {
            mvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer " + jwt)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void healthIsPublicAndProvisionalConsumerIsDisabled() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        assertThat(context.getBeansOfType(cl.duoc.eventomax.audit.consumer.ProductionEventConsumer.class)).isEmpty();
    }

    static AuditEvent event(String id, String actor, String type, LocalDateTime time) {
        var event = new AuditEvent(); event.setEventId(id); event.setActor(actor); event.setType(type);
        event.setTimestamp(time); event.setDetails("test fixture"); return event;
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
    static class Keys {
        @Bean @Primary
        JwtDecoder localTestDecoder() throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(AuditSecurityConfiguration.tokenValidator("https://issuer.invalid/test", "audit-test"));
            return decoder;
        }
    }
}
