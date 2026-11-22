package cl.duoc.eventomax.audit.integration.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "eventomax.graph")
public class AuditGraphProperties {

    private boolean enabled = false;
    private String tenantId = "";
    private String clientId = "";
    private String clientSecret = "";
    private String tokenBaseUrl = "https://login.microsoftonline.com";
    private String graphBaseUrl = "https://graph.microsoft.com/v1.0";
    private Duration cacheTtl = Duration.ofMinutes(10);
    private Duration notFoundCacheTtl = Duration.ofMinutes(2);
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getTokenBaseUrl() {
        return tokenBaseUrl;
    }

    public void setTokenBaseUrl(String tokenBaseUrl) {
        this.tokenBaseUrl = tokenBaseUrl;
    }

    public String getGraphBaseUrl() {
        return graphBaseUrl;
    }

    public void setGraphBaseUrl(String graphBaseUrl) {
        this.graphBaseUrl = graphBaseUrl;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public Duration getNotFoundCacheTtl() {
        return notFoundCacheTtl;
    }

    public void setNotFoundCacheTtl(Duration notFoundCacheTtl) {
        this.notFoundCacheTtl = notFoundCacheTtl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public boolean isConfigured() {
        return hasText(tenantId) && hasText(clientId) && hasText(clientSecret);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
