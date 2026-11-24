package cl.duoc.eventomax.audit.integration.graph;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas unitarias controladas sobre servidor HTTP local (NO constituyen consultas reales a Microsoft Entra ID).
 */
class MicrosoftGraphAuditActorProviderTest {

    private static final String TENANT_ID = "f191bdc8-7926-4722-9ec0-cba663701940";
    private static final String OID_WITH_MAIL = "e5660fea-1111-4444-8888-111111111111";
    private static final String OID_NO_MAIL = "c3057bf6-9e39-4fa4-b042-d3932d49ebc0";
    private static final String OID_404 = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String OID_403 = "bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String OID_503 = "cccccccc-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String OID_429 = "dddddddd-bbbb-4ccc-8ddd-eeeeeeeeeeee";

    private HttpServer server;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final Map<String, AtomicInteger> userCalls = new ConcurrentHashMap<>();

    @BeforeEach
    void startLocalServer() throws Exception {
        tokenCalls.set(0);
        userCalls.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/" + TENANT_ID + "/oauth2/v2.0/token", exchange -> {
            tokenCalls.incrementAndGet();
            byte[] body = "{\"access_token\":\"test-graph-token\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/v1.0/users/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String oid = path.substring(path.lastIndexOf('/') + 1);
            userCalls.computeIfAbsent(oid, k -> new AtomicInteger()).incrementAndGet();

            if (OID_WITH_MAIL.equals(oid)) {
                writeJson(exchange, 200, """
                        {"id":"%s","displayName":"Benjamin Espinoza","mail":"benja.espinoza@duocuc.cl","accountEnabled":true}
                        """.formatted(oid));
            } else if (OID_NO_MAIL.equals(oid)) {
                writeJson(exchange, 200, """
                        {"id":"%s","displayName":"Ignacio Valeria Nunez","mail":null,"otherMails":["ig_duoc#EXT#@tenant.onmicrosoft.com"],"accountEnabled":true}
                        """.formatted(oid));
            } else if (OID_404.equals(oid)) {
                writeJson(exchange, 404, "{\"error\":{\"code\":\"Request_ResourceNotFound\"}}");
            } else if (OID_403.equals(oid)) {
                writeJson(exchange, 403, "{\"error\":{\"code\":\"Authorization_RequestDenied\"}}");
            } else if (OID_503.equals(oid)) {
                writeJson(exchange, 503, "{\"error\":{\"code\":\"ServiceUnavailable\"}}");
            } else if (OID_429.equals(oid)) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                writeJson(exchange, 429, "{\"error\":{\"code\":\"TooManyRequests\"}}");
            } else {
                writeJson(exchange, 404, "{}");
            }
        });
        server.start();
    }

    @AfterEach
    void stopLocalServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AuditGraphProperties properties(boolean enabled) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var props = new AuditGraphProperties();
        props.setEnabled(enabled);
        props.setTenantId(TENANT_ID);
        props.setClientId("client-id-test");
        props.setClientSecret("client-secret-test");
        props.setTokenBaseUrl(base);
        props.setGraphBaseUrl(base + "/v1.0");
        props.setCacheTtl(Duration.ofMinutes(5));
        props.setNotFoundCacheTtl(Duration.ofMinutes(2));
        props.setConnectTimeout(Duration.ofSeconds(2));
        props.setReadTimeout(Duration.ofSeconds(2));
        return props;
    }

    @Test
    void returnsEmptyWithoutHttpCallsWhenDisabled() {
        var provider = new MicrosoftGraphAuditActorProvider(properties(false));
        assertThat(provider.findUserByCanonicalId(TENANT_ID + ":" + OID_WITH_MAIL)).isEmpty();
        assertThat(tokenCalls.get()).isZero();
    }

    @Test
    void rejectsDifferentTenantWithoutCallingGraph() {
        var provider = new MicrosoftGraphAuditActorProvider(properties(true));
        String otherTenant = "11111111-2222-4333-8444-555555555555:" + OID_WITH_MAIL;
        assertThat(provider.findUserByCanonicalId(otherTenant)).isEmpty();
        assertThat(tokenCalls.get()).isZero();
    }

    @Test
    void resolvesUserWithMailAndCachesSubsequentLookups() {
        var provider = new MicrosoftGraphAuditActorProvider(properties(true));
        String canonical = TENANT_ID + ":" + OID_WITH_MAIL;

        var first = provider.findUserByCanonicalId(canonical);
        var second = provider.findUserByCanonicalId(canonical);

        assertThat(first).isPresent();
        assertThat(first.get().displayName()).isEqualTo("Benjamin Espinoza");
        assertThat(first.get().email()).isEqualTo("benja.espinoza@duocuc.cl");
        assertThat(second).isEqualTo(first);
        assertThat(tokenCalls.get()).isEqualTo(1);
        assertThat(userCalls.get(OID_WITH_MAIL).get()).isEqualTo(1);
    }

    @Test
    void resolvesUserWithoutDeliverableMailAndDiscardsExtUpn() {
        var provider = new MicrosoftGraphAuditActorProvider(properties(true));
        var resolved = provider.findUserByCanonicalId(TENANT_ID + ":" + OID_NO_MAIL);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().displayName()).isEqualTo("Ignacio Valeria Nunez");
        assertThat(resolved.get().email()).isNull();
    }

    @Test
    void degradesSafelyOn403_404_429_503AndCaches404() {
        var provider = new MicrosoftGraphAuditActorProvider(properties(true));

        assertThat(provider.findUserByCanonicalId(TENANT_ID + ":" + OID_404)).isEmpty();
        assertThat(provider.findUserByCanonicalId(TENANT_ID + ":" + OID_404)).isEmpty();
        assertThat(userCalls.get(OID_404).get()).isEqualTo(1); // negative cached

        assertThat(provider.findUserByCanonicalId(TENANT_ID + ":" + OID_403)).isEmpty();
        assertThat(provider.findUserByCanonicalId(TENANT_ID + ":" + OID_429)).isEmpty();
        assertThat(provider.findUserByCanonicalId(TENANT_ID + ":" + OID_503)).isEmpty();
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, String json) throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
