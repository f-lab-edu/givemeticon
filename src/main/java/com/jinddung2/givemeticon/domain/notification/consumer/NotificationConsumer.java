package com.jinddung2.givemeticon.domain.notification.consumer;

import com.jinddung2.givemeticon.domain.notification.domain.Notification;
import com.jinddung2.givemeticon.domain.notification.domain.dto.CreateNotificationRequestDto;
import com.jinddung2.givemeticon.domain.notification.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationConsumer {
    private final NotificationMapper notificationMapper;

    @KafkaListener(topics = "alarm", groupId = "alarm", containerFactory = "notificationListenerContainerFactory")
    @Transactional
    public void consume(ConsumerRecord<String, CreateNotificationRequestDto> record) {
        CreateNotificationRequestDto request = record.value();
        int insertedRows = notificationMapper.saveIdempotently(new Notification(
                request.eventId(),
                request.saleId(),
                request.sellerId(),
                request.message(),
                false
        ));

        if (insertedRows == 0) {
            log.info("duplicated notification event ignored eventId={}", request.eventId());
            return;
        }

        log.info("consumed notification eventId={}, message={}", request.eventId(), request);
    }
}
