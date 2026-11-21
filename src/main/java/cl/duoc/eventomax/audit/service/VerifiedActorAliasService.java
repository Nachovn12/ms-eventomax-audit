package cl.duoc.eventomax.audit.service;

import cl.duoc.eventomax.audit.exception.ActorAliasConflictException;
import cl.duoc.eventomax.audit.integration.graph.AuditGraphProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URL;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class VerifiedActorAliasService {

    private static final Logger log = LoggerFactory.getLogger(VerifiedActorAliasService.class);
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final JdbcTemplate jdbc;
    private final String configuredIssuer;
    private final String configuredAudience;
    private final AuditGraphProperties graphProperties;

    @Autowired
    public VerifiedActorAliasService(
            JdbcTemplate jdbc,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String configuredIssuer,
            @Value("${eventomax.security.jwt.audience}") String configuredAudience,
            @Autowired(required = false) AuditGraphProperties graphProperties) {
        this.jdbc = jdbc;
        this.configuredIssuer = normalizeIssuer(configuredIssuer);
        this.configuredAudience = configuredAudience != null ? configuredAudience.trim() : "";
        this.graphProperties = graphProperties;
    }

    @Transactional
    public void registerFromValidatedJwt(Jwt jwt) {
        if (jwt == null) {
            throw new AccessDeniedException("JWT validado requerido para registrar asociación de identidad");
        }

        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new AccessDeniedException("El JWT no contiene un claim sub válido");
        }
        subject = subject.trim();

        String tid = jwt.getClaimAsString("tid");
        String oid = jwt.getClaimAsString("oid");
        if (tid == null || oid == null
                || !UUID_PATTERN.matcher(tid.trim()).matches()
                || !UUID_PATTERN.matcher(oid.trim()).matches()) {
            throw new AccessDeniedException("Se requieren claims tid y oid UUID válidos en el JWT");
        }
        String normalizedTid = tid.trim().toLowerCase(Locale.ROOT);
        String normalizedOid = oid.trim().toLowerCase(Locale.ROOT);
        String canonicalActorId = normalizedTid + ":" + normalizedOid;

        URL issuerUrl = jwt.getIssuer();
        if (issuerUrl == null) {
            throw new AccessDeniedException("El JWT no contiene un emisor válido");
        }
        String tokenIssuer = normalizeIssuer(issuerUrl.toString());
        if (!configuredIssuer.equalsIgnoreCase(tokenIssuer)) {
            throw new AccessDeniedException("Emisor del JWT incompatible con la configuración de Audit");
        }

        // Strict tenant consistency when issuer is Entra ID
        if ("login.microsoftonline.com".equalsIgnoreCase(issuerUrl.getHost())) {
            String expectedPath = "/" + normalizedTid + "/v2.0";
            if (!expectedPath.equalsIgnoreCase(issuerUrl.getPath())) {
                throw new AccessDeniedException("Tenant del JWT incompatible con el emisor Entra ID");
            }
        }
        if (graphProperties != null && graphProperties.getTenantId() != null && !graphProperties.getTenantId().isBlank()
                && !graphProperties.getTenantId().trim().equalsIgnoreCase(normalizedTid)) {
            throw new AccessDeniedException("Tenant del JWT incompatible con el tenant configurado");
        }

        List<String> audiences = jwt.getAudience();
        if (audiences == null || !audiences.contains(configuredAudience)) {
            throw new AccessDeniedException("Audiencia del JWT incompatible con la configuración de Audit");
        }

        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        int updated = jdbc.update("""
                INSERT INTO verified_actor_subject_alias (
                    issuer, audience, subject, canonical_actor_id, verified_at, last_seen_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (issuer, audience, subject) DO UPDATE SET
                    last_seen_at = EXCLUDED.last_seen_at
                WHERE verified_actor_subject_alias.canonical_actor_id = EXCLUDED.canonical_actor_id
                """,
                configuredIssuer,
                configuredAudience,
                subject,
                canonicalActorId,
                now,
                now
        );

        if (updated == 0) {
            log.warn("[AUDIT_ALIAS_CONFLICT] Intento de asociar un subject ya vinculado a otra identidad canónica (issuer={}).",
                    configuredIssuer);
            throw new ActorAliasConflictException(
                    "El subject autenticado ya está asociado a una identidad canónica diferente");
        }
    }

    @Transactional(readOnly = true)
    public Map<String, String> findCanonicalActorIdsBySubjects(Collection<String> subjects) {
        if (jdbc == null || subjects == null || subjects.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> distinctSubjects = subjects.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (distinctSubjects.isEmpty()) {
            return Collections.emptyMap();
        }

        String placeholders = String.join(",", Collections.nCopies(distinctSubjects.size(), "?"));
        String sql = "SELECT subject, canonical_actor_id FROM verified_actor_subject_alias "
                + "WHERE issuer = ? AND audience = ? AND subject IN (" + placeholders + ")";

        Object[] params = new Object[distinctSubjects.size() + 2];
        params[0] = configuredIssuer;
        params[1] = configuredAudience;
        for (int i = 0; i < distinctSubjects.size(); i++) {
            params[i + 2] = distinctSubjects.get(i);
        }

        Map<String, String> result = new HashMap<>();
        jdbc.query(sql, rs -> {
            result.put(rs.getString("subject"), rs.getString("canonical_actor_id"));
        }, params);
        return result;
    }

    private static String normalizeIssuer(String issuer) {
        if (issuer == null) {
            return "";
        }
        String trimmed = issuer.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
