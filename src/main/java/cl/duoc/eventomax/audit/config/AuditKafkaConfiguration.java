package cl.duoc.eventomax.audit.config;

import java.util.Map;
import cl.duoc.eventomax.audit.dto.ProductionEventMessage;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "eventomax.audit.kafka.enabled", havingValue = "true")
public class AuditKafkaConfiguration {
    @Bean
    DefaultKafkaProducerFactory<String, Object> auditProducerFactory(KafkaProperties kafkaProperties) {
        // Preserve configured broker security and producer options for retry/DLT too.
        var properties = kafkaProperties.buildProducerProperties();
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        var json = new JacksonJsonSerializer<ProductionEventMessage>();
        json.setAddTypeInfo(false);
        return new DefaultKafkaProducerFactory<>(properties, new StringSerializer(),
                new DelegatingByTypeSerializer(Map.of(
                        byte[].class, new ByteArraySerializer(), ProductionEventMessage.class, json)));
    }

    @Bean
    KafkaTemplate<String, Object> auditRetryKafkaTemplate(DefaultKafkaProducerFactory<String, Object> factory) {
        return new KafkaTemplate<>(factory);
    }
}
