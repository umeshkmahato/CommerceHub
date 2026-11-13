package com.commercehub.product.config;

import com.commercehub.product.event.ProductEvents.ProductEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

/**
 * Typed Kafka producer wiring.
 *
 * Why a dedicated KafkaTemplate bean instead of the auto-configured one:
 * Spring Boot's auto-configured template is KafkaTemplate<Object,Object> —
 * injecting KafkaTemplate<String, ProductEvent> into the publisher would
 * fail or require unchecked casts. Defining the bean with our event type
 * gives compile-time safety on what we send.
 *
 * JsonSerializer adds a __TypeId__ header so consumers can deserialize the
 * concrete record type; consumers must configure JsonDeserializer with
 * trusted packages (handled in each consuming service's KafkaConfig).
 */
@Configuration
public class KafkaConfig {

    @Bean
    public KafkaTemplate<String, ProductEvent> productEventKafkaTemplate(KafkaProperties props) {
        Map<String, Object> cfg = props.buildProducerProperties();
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(cfg));
    }
}
