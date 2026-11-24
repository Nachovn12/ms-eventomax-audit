package cl.duoc.eventomax.audit.integration.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Proveedor de solo lectura hacia Microsoft Graph REST v1.0 para resolver nombre visible y correo
 * entregable de un actor a partir de su identidad canónica {@code tid:oid}.
 * <p>
 * Deshabilitado por defecto ({@code eventomax.graph.enabled=false}).
 * Nunca expone credenciales ni tokens, valida el tenant configurado, usa caché con TTL y
 * degrada de forma segura ante errores 401/403/404/429/503 sin bloquear las consultas de Audit.
 */
@Component
public class MicrosoftGraphAuditActorProvider implements AuditActorDirectoryProvider {

    private static final Logger log = LoggerFactory.getLogger(MicrosoftGraphAuditActorProvider.class);
    private static final String UUID_REGEX = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern CANONICAL_PATTERN = Pattern.compile("^(" + UUID_REGEX + "):(" + UUID_REGEX + ")$");
    private static final Pattern DELIVERABLE_EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final long MAX_RETRY_AFTER_MS = 1_500L;

    private record CachedToken(String accessToken, Instant expiresAt) {}
    private record CachedUser(Optional<AuditDirectoryUser> user, Instant expiresAt) {}

    private final AuditGraphProperties properties;
    private final RestClient restClient;
    private final AtomicReference<CachedToken> tokenCache = new AtomicReference<>();
    private final ConcurrentHashMap<String, CachedUser> userCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> oidLocks = new ConcurrentHashMap<>();
    private final Object tokenLock = new Object();

    @Autowired
    public MicrosoftGraphAuditActorProvider(AuditGraphProperties properties) {
        this(properties, buildRestClient(properties));
    }

    public MicrosoftGraphAuditActorProvider(AuditGraphProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    private static RestClient buildRestClient(AuditGraphProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(props.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.getReadTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Optional<AuditDirectoryUser> findUserByCanonicalId(String canonicalActorId) {
        if (!properties.isEnabled() || !properties.isConfigured() || canonicalActorId == null) {
            return Optional.empty();
        }
        var matcher = CANONICAL_PATTERN.matcher(canonicalActorId.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String tid = matcher.group(1).toLowerCase(Locale.ROOT);
        String oid = matcher.group(2).toLowerCase(Locale.ROOT);
        if (!properties.getTenantId().trim().equalsIgnoreCase(tid)) {
            return Optional.empty();
        }

        CachedUser cached = userCache.get(oid);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.user();
        }

        Object lock = oidLocks.computeIfAbsent(oid, k -> new Object());
        synchronized (lock) {
            CachedUser doubleChecked = userCache.get(oid);
            if (doubleChecked != null && Instant.now().isBefore(doubleChecked.expiresAt())) {
                return doubleChecked.user();
            }
            return fetchAndCacheUser(oid);
        }
    }

    private Optional<AuditDirectoryUser> fetchAndCacheUser(String oid) {
        try {
            String accessToken = acquireAccessToken();
            if (accessToken == null) {
                return Optional.empty();
            }
            Optional<AuditDirectoryUser> resolved = fetchUserByOid(accessToken, oid, false);
            if (resolved.isPresent()) {
                userCache.put(oid, new CachedUser(resolved, Instant.now().plus(properties.getCacheTtl())));
            }
            return resolved;
        } catch (HttpClientErrorException.NotFound nf) {
            userCache.put(oid, new CachedUser(Optional.empty(), Instant.now().plus(properties.getNotFoundCacheTtl())));
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized unauth) {
            tokenCache.set(null);
            log.warn("[AUDIT_GRAPH_UNAUTHORIZED] Token rechazado por Microsoft Graph (401).");
            return Optional.empty();
        } catch (HttpClientErrorException.Forbidden forbidden) {
            log.warn("[AUDIT_GRAPH_FORBIDDEN] Permiso insuficiente en Microsoft Graph (403).");
            return Optional.empty();
        } catch (HttpClientErrorException.TooManyRequests rateLimited) {
            log.warn("[AUDIT_GRAPH_THROTTLED] Microsoft Graph respondió 429; degradando sin bloquear Audit.");
            return Optional.empty();
        } catch (HttpServerErrorException serverError) {
            log.warn("[AUDIT_GRAPH_UNAVAILABLE] Microsoft Graph respondió {} ; degradando sin bloquear Audit.",
                    serverError.getStatusCode().value());
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("[AUDIT_GRAPH_ERROR] Error al consultar Microsoft Graph ({}); degradando sin bloquear Audit.",
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<AuditDirectoryUser> fetchUserByOid(String accessToken, String oid, boolean retriedRateLimit) {
        String baseUrl = trimTrailingSlash(properties.getGraphBaseUrl());
        String url = baseUrl + "/users/" + oid + "?$select=id,displayName,mail,otherMails,accountEnabled";
        try {
            JsonNode body = restClient.get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null || body.isNull()) {
                return Optional.empty();
            }
            String displayName = normalizeText(textOrNull(body.get("displayName")), 150);
            String email = extractDeliverableEmail(body);
            if (displayName == null && email == null) {
                return Optional.empty();
            }
            return Optional.of(new AuditDirectoryUser(oid, displayName, email));
        } catch (HttpClientErrorException.TooManyRequests ex) {
            if (!retriedRateLimit) {
                long delayMs = parseBoundedRetryAfterMs(ex.getResponseHeaders());
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return Optional.empty();
                    }
                    return fetchUserByOid(accessToken, oid, true);
                }
            }
            throw ex;
        }
    }

    private String acquireAccessToken() {
        CachedToken cached = tokenCache.get();
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.accessToken();
        }
        synchronized (tokenLock) {
            CachedToken rechecked = tokenCache.get();
            if (rechecked != null && Instant.now().isBefore(rechecked.expiresAt())) {
                return rechecked.accessToken();
            }
            String tokenUrl = trimTrailingSlash(properties.getTokenBaseUrl())
                    + "/" + properties.getTenantId().trim() + "/oauth2/v2.0/token";

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", properties.getClientId().trim());
            form.add("client_secret", properties.getClientSecret());
            form.add("scope", "https://graph.microsoft.com/.default");

            JsonNode response = restClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.hasNonNull("access_token")) {
                return null;
            }
            String token = response.get("access_token").asText();
            long expiresIn = response.hasNonNull("expires_in") ? response.get("expires_in").asLong(300L) : 300L;
            long safeTtlSeconds = Math.max(30L, expiresIn - 60L);
            tokenCache.set(new CachedToken(token, Instant.now().plusSeconds(safeTtlSeconds)));
            return token;
        }
    }

    private static String extractDeliverableEmail(JsonNode node) {
        String mail = textOrNull(node.get("mail"));
        if (isDeliverableEmail(mail)) {
            return mail.trim();
        }
        JsonNode otherMails = node.get("otherMails");
        if (otherMails != null && otherMails.isArray()) {
            for (JsonNode entry : otherMails) {
                String candidate = textOrNull(entry);
                if (isDeliverableEmail(candidate)) {
                    return candidate.trim();
                }
            }
        }
        return null;
    }

    public static boolean isDeliverableEmail(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String trimmed = candidate.trim();
        if (trimmed.length() > 255 || trimmed.toUpperCase(Locale.ROOT).contains("#EXT#")) {
            return false;
        }
        return DELIVERABLE_EMAIL_PATTERN.matcher(trimmed).matches();
    }

    private static long parseBoundedRetryAfterMs(HttpHeaders headers) {
        if (headers == null) {
            return 0L;
        }
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter == null || retryAfter.isBlank()) {
            return 0L;
        }
        try {
            long seconds = Long.parseLong(retryAfter.trim());
            if (seconds <= 0) {
                return 0L;
            }
            return Math.min(seconds * 1000L, MAX_RETRY_AFTER_MS);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String textOrNull(JsonNode node) {
        return (node != null && !node.isNull()) ? node.asText() : null;
    }

    private static String normalizeText(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxLen ? trimmed.substring(0, maxLen) : trimmed;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
