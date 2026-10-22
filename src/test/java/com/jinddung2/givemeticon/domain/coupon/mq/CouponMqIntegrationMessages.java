package com.jinddung2.givemeticon.domain.coupon.mq;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/** 다른 패키지의 통합 테스트가 package-private인 {@link CouponMqProtocol#payload}로 MQ 메시지를 만들 수 있게 하는 시험 전용 도우미다. */
public final class CouponMqIntegrationMessages {

    private CouponMqIntegrationMessages() {
    }

    public static Message admit(long eventId, int memberId, String attemptId, String configVersion, String openToken) {
        MessageProperties properties = new MessageProperties();
        properties.setConsumerQueue(CouponMqConfiguration.queue(eventId));
        properties.setReceivedExchange(CouponMqConfiguration.exchange(eventId));
        properties.setReceivedRoutingKey("admit");
        return new Message(CouponMqProtocol.payload(eventId, memberId, attemptId, configVersion, openToken), properties);
    }
}
