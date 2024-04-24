package com.jinddung2.givemeticon.domain.notification.consumer;

import com.jinddung2.givemeticon.domain.notification.domain.Notification;
import com.jinddung2.givemeticon.domain.notification.domain.dto.CreateNotificationRequestDto;
import com.jinddung2.givemeticon.domain.notification.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationConsumer {
    private final NotificationMapper notificationMapper;

    @KafkaListener(topics = "alarm", groupId = "alarm", containerFactory = "notificationListenerContainerFactory")
    public void consume(ConsumerRecord<String, CreateNotificationRequestDto> record) {
        try {
            notificationMapper.save(new Notification(
                    record.value().saleId(),
                    record.value().sellerId(),
                    record.value().message(),
                    false
            ));
            log.info("consumed message={}", record.value());
        } catch (KafkaException e) {
            log.error("failed to create alarm:: msg={}", e.getMessage());
        }
    }
}