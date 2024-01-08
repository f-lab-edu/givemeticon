package com.jinddung2.givemeticon.domain.notification.consumer;

import com.jinddung2.givemeticon.domain.notification.domain.Notification;
import com.jinddung2.givemeticon.domain.notification.domain.dto.CreateNotificationRequestDto;
import com.jinddung2.givemeticon.domain.notification.mapper.NotificationMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

    @InjectMocks
    NotificationConsumer notificationConsumer;

    @Mock
    NotificationMapper notificationMapper;

    int saleId = 1;
    int sellerId = 2;
    int notificationId= 3;
    @Test
    @DisplayName("알람 요청을 consume 해서 DB에 저장한다.")
    void consume() {
        CreateNotificationRequestDto requestFakeDto = new CreateNotificationRequestDto(saleId, sellerId, "fakeMessage");
        ConsumerRecord<String, CreateNotificationRequestDto> record =
                new ConsumerRecord<>("fakeTopic", 0, 0, "fakeKey", requestFakeDto);

        Mockito.when(notificationMapper.save(Mockito.any(Notification.class))).thenReturn(notificationId);

        notificationConsumer.consume(record);

        Mockito.verify(notificationMapper).save(Mockito.any(Notification.class));
    }
}