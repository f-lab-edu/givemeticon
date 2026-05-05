package com.jinddung2.givemeticon.domain.notification.consumer;

import com.jinddung2.givemeticon.domain.notification.domain.Notification;
import com.jinddung2.givemeticon.domain.notification.domain.dto.CreateNotificationRequestDto;
import com.jinddung2.givemeticon.domain.notification.mapper.NotificationMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;

@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

    @Mock
    NotificationMapper notificationMapper;

    @Test
    @DisplayName("알람 요청을 consume 해서 DB에 저장한다.")
    void consume() {
        NotificationConsumer sut = new NotificationConsumer(notificationMapper);
        int saleId = 1, sellerId = 2, notificationId = 3;
        CreateNotificationRequestDto requestFakeDto = new CreateNotificationRequestDto(saleId, sellerId, "fakeMessage");
        ConsumerRecord<String, CreateNotificationRequestDto> record =
                new ConsumerRecord<>("fakeTopic", 0, 0, "fakeKey", requestFakeDto);

        Mockito.when(notificationMapper.saveIdempotently(Mockito.any(Notification.class))).thenReturn(notificationId);

        sut.consume(record);

        Mockito.verify(notificationMapper).saveIdempotently(Mockito.argThat(notification ->
                notification.getEventId().equals(requestFakeDto.eventId())
                        && notification.getSaleId() == saleId
                        && notification.getSellerId() == sellerId
                        && notification.getMessage().equals("fakeMessage")
                        && !notification.isRead()
        ));
    }

    @Test
    @DisplayName("동일한 eventId가 재전달되어도 알람은 한 번만 저장된다.")
    void consume_duplicate_delivery_is_idempotent() {
        InMemoryIdempotentNotificationMapper mapper = new InMemoryIdempotentNotificationMapper();
        NotificationConsumer sut = new NotificationConsumer(mapper);
        CreateNotificationRequestDto request = new CreateNotificationRequestDto(
                "event-1", 1, 2, "fakeMessage"
        );
        ConsumerRecord<String, CreateNotificationRequestDto> record =
                new ConsumerRecord<>("fakeTopic", 0, 0, "event-1", request);

        sut.consume(record);
        sut.consume(record);

        Assertions.assertThat(mapper.insertedCount()).isEqualTo(1);
        Assertions.assertThat(mapper.duplicateCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("eventId가 없으면 payload 기반으로 같은 idempotency key를 생성한다.")
    void createNotificationRequestDto_derives_event_id_from_payload() {
        CreateNotificationRequestDto first = new CreateNotificationRequestDto(1, 2, "fakeMessage");
        CreateNotificationRequestDto duplicate = new CreateNotificationRequestDto(1, 2, "fakeMessage");
        CreateNotificationRequestDto different = new CreateNotificationRequestDto(1, 2, "otherMessage");

        Assertions.assertThat(first.eventId()).isEqualTo(duplicate.eventId());
        Assertions.assertThat(first.eventId()).isNotEqualTo(different.eventId());
    }

    private static class InMemoryIdempotentNotificationMapper implements NotificationMapper {
        private final Set<String> eventIds = new HashSet<>();
        private int duplicateCount;

        @Override
        public int saveIdempotently(Notification notification) {
            if (eventIds.add(notification.getEventId())) {
                return 1;
            }
            duplicateCount++;
            return 0;
        }

        int insertedCount() {
            return eventIds.size();
        }

        int duplicateCount() {
            return duplicateCount;
        }
    }
}
