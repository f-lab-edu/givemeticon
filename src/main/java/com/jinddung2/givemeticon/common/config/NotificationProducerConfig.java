package com.jinddung2.givemeticon.common.config;

import com.jinddung2.givemeticon.domain.notification.domain.dto.CreateNotificationRequestDto;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class NotificationProducerConfig {

    @Bean
    public KafkaTemplate<String, CreateNotificationRequestDto> notificationKafkaTemplate() {
        return new KafkaTemplate<>(notificationProducerFactory());
    }

    @Bean
    public ProducerFactory<String, CreateNotificationRequestDto> notificationProducerFactory() {
        return new DefaultKafkaProducerFactory<>(notificationProducerConfigs());
    }

    // FIXME: bootstrap-server 변경 예정
    @Bean
    public Map<String, Object> notificationProducerConfigs() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return props;
    }
}
