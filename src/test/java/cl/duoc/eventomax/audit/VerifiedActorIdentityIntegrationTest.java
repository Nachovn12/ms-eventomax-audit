package cl.duoc.eventomax.audit;

import cl.duoc.eventomax.audit.integration.graph.AuditActorDirectoryProvider;
import cl.duoc.eventomax.audit.integration.graph.AuditDirectoryUser;
import cl.duoc.eventomax.audit.model.AuditEvent;
import cl.duoc.eventomax.audit.security.AuditSecurityConfiguration;
import cl.duoc.eventomax.audit.service.AuditService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración PostgreSQL 17 + Flyway V7/V8 + Spring Security para registro de alias verificados
 * y resolución de identidad de actores (usa doble de prueba para Graph; NO consulta Entra ID real).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(VerifiedActorIdentityIntegrationTest.TestConfig.class)
class VerifiedActorIdentityIntegrationTest {

    private static final String ISSUER = "https://issuer.invalid/test";
    private static final String AUDIENCE = "audit-test";
    private static final String TENANT_ID = "f191bdc8-7926-4722-9ec0-cba663701940";
    private static final String OID_IGNACIO = "c3057bf6-9e39-4fa4-b042-d3932d49ebc0";
    private static final String OID_BENJAMIN = "e5660fea-1111-4444-8888-111111111111";
    private static final String SUB_VERIFIED = "sqdd0RRocgoh8Vpb8bmF2B7ad03kqIPhbn_Fs07K9ds";
    private static final String SUB_UNVERIFIED = "unverifiedSubValue99887766554433221100AA";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static final RSAKey KEY = newKey();
    static final Map<String, AuditDirectoryUser> STUB_DIRECTORY = new ConcurrentHashMap<>();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired AuditService auditService;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE audit_event, processed_event, production_timeline_snapshot, verified_actor_subject_alias RESTART IDENTITY");
        STUB_DIRECTORY.clear();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void flywayV7AndV8AppliedAndAllFourRolesCanRegisterOwnAliasWithoutGainingAuditReadAccess() throws Exception {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(versions).contains("7", "8");

        int idx = 1;
        for (String role : List.of("Admin", "Productor", "Organizador", "Auditor")) {
            String oid = "00000000-0000-4000-8000-00000000000" + idx;
            String sub = "sub-role-" + role + "-" + idx;
            String jwt = token(sub, TENANT_ID, oid, role, "access_as_user");

            // Empty-body request registers strictly from validated JWT -> 204 No Content
            mvc.perform(post("/api/audit/actor-aliases/me")
                            .header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isNoContent());

            String persisted = jdbc.queryForObject(
                    "SELECT canonical_actor_id FROM verified_actor_subject_alias WHERE issuer = ? AND audience = ? AND subject = ?",
                    String.class, ISSUER, AUDIENCE, sub);
            assertThat(persisted).isEqualTo(TENANT_ID + ":" + oid);

            // Productor and Organizador MUST remain blocked from reading Audit timeline
            if ("Productor".equals(role) || "Organizador".equals(role)) {
                mvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer " + jwt))
                        .andExpect(status().isForbidden());
                mvc.perform(get("/api/audit/timeline/latest").header("Authorization", "Bearer " + jwt))
                        .andExpect(status().isForbidden());
            } else {
                mvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer " + jwt))
                        .andExpect(status().isOk());
            }
            idx++;
        }
    }

    @Test
    void rejectsNonEmptyRequestBodyWith400WithoutPersistingAnyAlias() throws Exception {
        String jwt = token(SUB_VERIFIED, TENANT_ID, OID_IGNACIO, "Productor", "access_as_user");

        // JSON with arbitrary identifiers -> 400 Bad Request
        mvc.perform(post("/api/audit/actor-aliases/me")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sub\":\"spoofed\",\"tid\":\"" + TENANT_ID + "\",\"oid\":\"" + OID_BENJAMIN + "\",\"canonicalActorId\":\"spoofed:spoofed\"}"))
                .andExpect(status().isBadRequest());

        // Empty JSON object still counts as non-empty body -> 400 Bad Request
        mvc.perform(post("/api/audit/actor-aliases/me")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // Plain text body -> 400 Bad Request
        mvc.perform(post("/api/audit/actor-aliases/me")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("unexpected"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM verified_actor_subject_alias", Integer.class)).isZero();
    }

    @Test
    void rejectsMissingScopeMissingClaimsAndContradictoryBindingAndSupportsConcurrency() throws Exception {
        // Missing token -> 401
        mvc.perform(post("/api/audit/actor-aliases/me")).andExpect(status().isUnauthorized());

        // Missing access_as_user -> 403
        String wrongScope = token(SUB_VERIFIED, TENANT_ID, OID_IGNACIO, "Productor", "other_scope");
        mvc.perform(post("/api/audit/actor-aliases/me").header("Authorization", "Bearer " + wrongScope))
                .andExpect(status().isForbidden());

        // Missing tid/oid -> 403
        String noOid = token(SUB_VERIFIED, TENANT_ID, null, "Productor", "access_as_user");
        mvc.perform(post("/api/audit/actor-aliases/me").header("Authorization", "Bearer " + noOid))
                .andExpect(status().isForbidden());

        // Concurrent valid registrations of same (iss, aud, sub) -> all 204, 1 row persisted
        String validJwt = token(SUB_VERIFIED, TENANT_ID, OID_IGNACIO, "Productor", "access_as_user");
        var ready = new CountDownLatch(6);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return mvc.perform(post("/api/audit/actor-aliases/me")
                                    .header("Authorization", "Bearer " + validJwt))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var f : futures) {
                assertThat(f.get(15, TimeUnit.SECONDS)).isEqualTo(204);
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verified_actor_subject_alias", Integer.class)).isEqualTo(1);

        // Contradictory binding (same sub, DIFFERENT oid) -> 409 Conflict, original preserved
        String conflictingJwt = token(SUB_VERIFIED, TENANT_ID, OID_BENJAMIN, "Productor", "access_as_user");
        mvc.perform(post("/api/audit/actor-aliases/me").header("Authorization", "Bearer " + conflictingJwt))
                .andExpect(status().isConflict());

        String stillOriginal = jdbc.queryForObject(
                "SELECT canonical_actor_id FROM verified_actor_subject_alias WHERE subject = ?",
                String.class, SUB_VERIFIED);
        assertThat(stillOriginal).isEqualTo(TENANT_ID + ":" + OID_IGNACIO);
    }

    @Test
    void resolvesTimelineFollowingStrictPriorityWithoutMutatingHistoricalRecords() throws Exception {
        // Register verified alias for SUB_VERIFIED -> TENANT_ID:OID_IGNACIO
        String prodJwt = token(SUB_VERIFIED, TENANT_ID, OID_IGNACIO, "Productor", "access_as_user");
        mvc.perform(post("/api/audit/actor-aliases/me").header("Authorization", "Bearer " + prodJwt))
                .andExpect(status().isNoContent());

        // Controlled Graph stub entries (Ignacio without mail; Benjamin with mail)
        STUB_DIRECTORY.put(TENANT_ID + ":" + OID_IGNACIO, new AuditDirectoryUser(OID_IGNACIO, "Ignacio Valeria Nunez", null));
        STUB_DIRECTORY.put(TENANT_ID + ":" + OID_BENJAMIN, new AuditDirectoryUser(OID_BENJAMIN, "Benjamin Espinoza", "benja.espinoza@duocuc.cl"));

        LocalDateTime t = LocalDateTime.of(2026, 11, 21, 10, 0);
        // 1. SYSTEM
        auditService.processEvent(event("e-sys", "system", null, t.plusMinutes(6)));
        // 2. EVENT (persisted actorDisplayName takes priority over directory)
        auditService.processEvent(event("e-evt", TENANT_ID + ":" + OID_BENJAMIN, "Nombre En Evento", t.plusMinutes(5)));
        // 3. ENTRA_DIRECTORY (historical tid:oid with mail)
        auditService.processEvent(event("e-dir", TENANT_ID + ":" + OID_BENJAMIN, null, t.plusMinutes(4)));
        // 4. VERIFIED_SUB_TO_ENTRA_DIRECTORY (historical sub with verified alias, user without mail)
        auditService.processEvent(event("e-sub-ok", SUB_VERIFIED, null, t.plusMinutes(3)));
        // 5. LEGACY (readable alias)
        auditService.processEvent(event("e-leg", "usuario-demo", null, t.plusMinutes(2)));
        // 6. UNRESOLVED (unverified opaque sub)
        auditService.processEvent(event("e-unres", SUB_UNVERIFIED, null, t.plusMinutes(1)));

        String auditorJwt = token("auditor-sub", TENANT_ID, OID_IGNACIO, "Auditor", "access_as_user");

        mvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer " + auditorJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                // 1. SYSTEM
                .andExpect(jsonPath("$[0].eventId").value("e-sys"))
                .andExpect(jsonPath("$[0].actor").value("system"))
                .andExpect(jsonPath("$[0].actorResolutionSource").value("SYSTEM"))
                // 2. EVENT
                .andExpect(jsonPath("$[1].eventId").value("e-evt"))
                .andExpect(jsonPath("$[1].actorDisplayName").value("Nombre En Evento"))
                .andExpect(jsonPath("$[1].actorEmail").value((Object) null))
                .andExpect(jsonPath("$[1].actorResolutionSource").value("EVENT"))
                // 3. ENTRA_DIRECTORY
                .andExpect(jsonPath("$[2].eventId").value("e-dir"))
                .andExpect(jsonPath("$[2].actor").value(TENANT_ID + ":" + OID_BENJAMIN))
                .andExpect(jsonPath("$[2].actorDisplayName").value("Benjamin Espinoza"))
                .andExpect(jsonPath("$[2].actorEmail").value("benja.espinoza@duocuc.cl"))
                .andExpect(jsonPath("$[2].actorResolutionSource").value("ENTRA_DIRECTORY"))
                // 4. VERIFIED_SUB_TO_ENTRA_DIRECTORY
                .andExpect(jsonPath("$[3].eventId").value("e-sub-ok"))
                .andExpect(jsonPath("$[3].actor").value(SUB_VERIFIED))
                .andExpect(jsonPath("$[3].actorDisplayName").value("Ignacio Valeria Nunez"))
                .andExpect(jsonPath("$[3].actorEmail").value((Object) null))
                .andExpect(jsonPath("$[3].actorResolutionSource").value("VERIFIED_SUB_TO_ENTRA_DIRECTORY"))
                // 5. LEGACY
                .andExpect(jsonPath("$[4].eventId").value("e-leg"))
                .andExpect(jsonPath("$[4].actor").value("usuario-demo"))
                .andExpect(jsonPath("$[4].actorResolutionSource").value("LEGACY"))
                // 6. UNRESOLVED
                .andExpect(jsonPath("$[5].eventId").value("e-unres"))
                .andExpect(jsonPath("$[5].actor").value(SUB_UNVERIFIED))
                .andExpect(jsonPath("$[5].actorDisplayName").value((Object) null))
                .andExpect(jsonPath("$[5].actorResolutionSource").value("UNRESOLVED"));

        // Exact technical actor filter still works unchanged and historical DB row was NEVER modified
        mvc.perform(get("/api/audit/timeline")
                        .header("Authorization", "Bearer " + auditorJwt)
                        .param("actor", SUB_VERIFIED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].actorDisplayName").value("Ignacio Valeria Nunez"));

        String dbDisplayName = jdbc.queryForObject(
                "SELECT actor_display_name FROM audit_event WHERE event_id = 'e-sub-ok'", String.class);
        assertThat(dbDisplayName).isNull();
    }

    private static AuditEvent event(String id, String actor, String displayName, LocalDateTime time) {
        var e = new AuditEvent();
        e.setEventId(id);
        e.setActor(actor);
        e.setActorDisplayName(displayName);
        e.setType("ProductionStatusChanged");
        e.setTimestamp(time);
        e.setDetails("{\"productionId\":9}");
        return e;
    }

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token(String sub, String tid, String oid, String role, String scope) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .subject(sub)
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .claim("roles", List.of(role))
                .claim("scp", scope)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
        if (tid != null) claims.claim("tid", tid);
        if (oid != null) claims.claim("oid", oid);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        JwtDecoder localTestDecoder() throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(AuditSecurityConfiguration.tokenValidator(ISSUER, AUDIENCE));
            return decoder;
        }

        @Bean
        @Primary
        AuditActorDirectoryProvider stubDirectoryProvider() {
            return canonicalActorId -> Optional.ofNullable(STUB_DIRECTORY.get(canonicalActorId));
        }
    }
}
