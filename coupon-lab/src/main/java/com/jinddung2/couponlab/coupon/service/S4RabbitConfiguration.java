package com.jinddung2.couponlab.coupon.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * S4 broker topology: a durable direct exchange bound to the durable queue coupon.issued (declared by RabbitAdmin on
 * connect), JSON messages, and a log line for a message the broker could not route (it is still confirmed).
 */
@Configuration
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "s4")
public class S4RabbitConfiguration {

    public static final String EXCHANGE = "coupon.direct";
    public static final String QUEUE = "coupon.issued";
    public static final String ROUTING_KEY = "coupon.issued";

    private static final Logger log = LoggerFactory.getLogger(S4RabbitConfiguration.class);

    @Bean
    DirectExchange couponExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue couponIssuedQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    Binding couponIssuedBinding(Queue couponIssuedQueue, DirectExchange couponExchange) {
        return BindingBuilder.bind(couponIssuedQueue).to(couponExchange).with(ROUTING_KEY);
    }

    @Bean
    MessageConverter couponMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    RabbitTemplateCustomizer couponReturnsLogger() {
        return template -> template.setReturnsCallback(returned -> log.error(
                "publish returned unroutable (the broker confirmed it, the win has no message): replyText={} body={}",
                returned.getReplyText(), new String(returned.getMessage().getBody())));
    }
}
