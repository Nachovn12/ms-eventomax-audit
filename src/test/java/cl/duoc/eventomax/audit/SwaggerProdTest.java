package cl.duoc.eventomax.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "eventomax.audit.kafka.enabled=false"
})
@ActiveProfiles("prod")
@Testcontainers
class SwaggerProdTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("DB_URL", POSTGRES::getJdbcUrl);
        r.add("DB_USER", POSTGRES::getUsername);
        r.add("DB_PASSWORD", POSTGRES::getPassword);
        r.add("ENTRA_ISSUER_URI", () -> "https://issuer.invalid");
        r.add("ENTRA_AUDIENCE", () -> "api-audit");
    }

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void noSwaggerMappingsInProd() {
        boolean hasSwagger = handlerMapping.getHandlerMethods().keySet().stream()
                .anyMatch(info -> info.toString().contains("/v3/api-docs") || info.toString().contains("swagger"));
        
        assertThat(hasSwagger).isFalse();
    }
}

