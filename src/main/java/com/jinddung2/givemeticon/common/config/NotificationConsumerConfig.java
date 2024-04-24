package com.jinddung2.givemeticon.common.config;

import com.jinddung2.givemeticon.domain.notification.domain.dto.CreateNotificationRequestDto;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class NotificationConsumerConfig {

    @Value("${bootstrap.server}")
    private String bootstrapServer;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CreateNotificationRequestDto> notificationListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, CreateNotificationRequestDto> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(notificationConsumerFactory());

        return factory;
    }

    @Bean
    public ConsumerFactory<String, CreateNotificationRequestDto> notificationConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(
                notificationConsumerConfigs(),
                new StringDeserializer(),
                new JsonDeserializer<>(CreateNotificationRequestDto.class));
    }

    // FIXME: bootstrap-server 변경 예정
    private Map<String, Object> notificationConsumerConfigs() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServer);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "alarm");
        return props;
    }
}
