package com.jinddung2.couponlab.coupon.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * S6 broker topology. Its own names (S4/S5 keep coupon.issued): a durable direct exchange and a classic durable queue
 * with a length limit and overflow reject-publish (the default, drop-head, would silently discard requests the broker
 * already confirmed; with reject-publish a full queue nacks the publish and the request is answered 503), and a dead-letter
 * exchange with a durable dead-letter queue for the messages the Consumer rejects without requeue. No quorum queue: one node.
 * The listener container factory is S6's own (the shared spring.rabbitmq.listener.* is untouched): one consumer thread,
 * prefetch 50 as in S4, AUTO ack, rejected messages are requeued unless the Consumer says otherwise, and a message that
 * cannot be converted (broken JSON) is rejected without requeue by the error handler, which also counts it.
 */
@Configuration
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "s6")
public class S6RabbitConfiguration {

    public static final String EXCHANGE = "coupon.s6.direct";
    public static final String QUEUE = "coupon.s6.requests";
    public static final String ROUTING_KEY = "coupon.s6.requests";
    public static final String DEAD_LETTER_EXCHANGE = "coupon.s6.dlx";
    public static final String DEAD_LETTER_QUEUE = "coupon.s6.requests.dlq";
    public static final String LISTENER_ID = "s6RequestConsumer";
    public static final String LISTENER_CONTAINER_FACTORY = "s6ListenerContainerFactory";
    public static final int MAX_LENGTH = 200_000;
    static final int PREFETCH = 50;
    static final long SHUTDOWN_TIMEOUT_MS = 1000;

    private static final Logger log = LoggerFactory.getLogger(S6RabbitConfiguration.class);

    @Bean
    DirectExchange s6Exchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    DirectExchange s6DeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue s6RequestQueue(@Value("${COUPON_S6_QUEUE_MAX_LENGTH:" + MAX_LENGTH + "}") int maxLength) {
        return QueueBuilder.durable(QUEUE)
                .maxLength(maxLength)
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    Queue s6DeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding s6RequestBinding(Queue s6RequestQueue, DirectExchange s6Exchange) {
        return BindingBuilder.bind(s6RequestQueue).to(s6Exchange).with(ROUTING_KEY);
    }

    @Bean
    Binding s6DeadLetterBinding(Queue s6DeadLetterQueue, DirectExchange s6DeadLetterExchange) {
        return BindingBuilder.bind(s6DeadLetterQueue).to(s6DeadLetterExchange).with(DEAD_LETTER_QUEUE);
    }

    @Bean
    MessageConverter s6MessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    RabbitTemplateCustomizer s6ReturnsLogger() {
        return template -> template.setReturnsCallback(returned -> log.error(
                "publish returned unroutable (the broker confirmed it, the request has no queue): replyText={} body={}",
                returned.getReplyText(), new String(returned.getMessage().getBody())));
    }

    @Bean(LISTENER_CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory s6ListenerContainerFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory, MeterRegistry meterRegistry) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(PREFETCH);
        factory.setDefaultRequeueRejected(true);
        factory.setContainerCustomizer(container -> container.setShutdownTimeout(SHUTDOWN_TIMEOUT_MS));
        factory.setErrorHandler(new ConditionalRejectingErrorHandler() {
            @Override
            public void handleError(Throwable t) {
                if (causedBy(t, MessageConversionException.class)) {
                    meterRegistry.counter("coupon_s6_dlq", "reason", "conversion").increment();
                }
                super.handleError(t);
            }
        });
        return factory;
    }

    static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
