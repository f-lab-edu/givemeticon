package com.jinddung2.givemeticon.domain.notification.producer;

import com.jinddung2.givemeticon.domain.notification.domain.dto.CreateNotificationRequestDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class NotificationProducerTest {

    @InjectMocks
    private NotificationProducer notificationProducer;

    @Mock
    private KafkaTemplate<String, CreateNotificationRequestDto> kafkaTemplate;

    int saleId = 1;
    int sellerId = 2;

    @Test
    @DisplayName("알람 요청을 만들어 \"alarm\" 이라는 토픽을 카프카 큐에 넣는다.")
    void create() {
        CreateNotificationRequestDto fakeDto =
                new CreateNotificationRequestDto(saleId, sellerId, "fakeMessage");

        notificationProducer.create(fakeDto);

        Mockito.verify(kafkaTemplate).send(Mockito.eq("alarm"), Mockito.eq(fakeDto));
    }
}