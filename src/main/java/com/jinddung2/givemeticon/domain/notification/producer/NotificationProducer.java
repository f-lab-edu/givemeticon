package com.jinddung2.givemeticon.domain.notification.producer;

import com.jinddung2.givemeticon.domain.notification.domain.dto.CreateNotificationRequestDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class NotificationProducer {

    private final String TOPIC_NAME = "alarm";
    private final KafkaTemplate<String, CreateNotificationRequestDto> kafkaTemplate;

    public NotificationProducer(KafkaTemplate<String, CreateNotificationRequestDto> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void create(CreateNotificationRequestDto request) {
        kafkaTemplate.send(TOPIC_NAME, request);
        log.info("kafka producer message request={}", request);
    }
}
