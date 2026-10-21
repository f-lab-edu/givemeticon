package com.jinddung2.givemeticon.domain.coupon.mq;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import static org.junit.jupiter.api.Assertions.*;
class CouponMqProfileTest {
    @Test void profileOffHasNoPublisherConnectionOrListener() {
        new ApplicationContextRunner().withUserConfiguration(CouponMqConfiguration.class,
                CouponMqReadinessService.class,CouponMqReadinessTransactionService.class,
                CouponMqAdmissionTransactionService.class,CouponMqAdmissionService.class,
                com.jinddung2.givemeticon.domain.coupon.controller.CouponMqAdmissionController.class).run(context->{
            assertEquals(0,context.getBeansOfType(CouponMqPublisher.class).size());
            assertEquals(0,context.getBeansOfType(org.springframework.amqp.rabbit.connection.ConnectionFactory.class).size());
            assertEquals(0,context.getBeansOfType(SimpleMessageListenerContainer.class).size());
            assertEquals(0,context.getBeansOfType(CouponMqReadinessService.class).size());
        });
    }
}
