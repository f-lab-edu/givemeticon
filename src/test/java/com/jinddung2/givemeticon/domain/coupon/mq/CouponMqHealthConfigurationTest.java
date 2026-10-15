package com.jinddung2.givemeticon.domain.coupon.mq;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.actuate.autoconfigure.amqp.RabbitHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CouponMqHealthConfigurationTest {
    final ConnectionFactory factory=mock(ConnectionFactory.class);
    ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withInitializer(ctx->{
                    var resource=new ClassPathResource("application.properties");
                    // Missing packaged configuration must expose the default enabled health regression.
                    try { if(resource.exists()) ctx.getEnvironment().getPropertySources().addLast(new ResourcePropertySource(resource)); }
                    catch(java.io.IOException e) { throw new IllegalStateException("cannot load packaged default",e); }
                })
                .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class,
                        RabbitHealthContributorAutoConfiguration.class,HealthContributorAutoConfiguration.class))
                .withBean(ConnectionFactory.class,()->factory);
    }
    @Test void packagedDefaultDisablesOnlyRabbitHealth() {
        context().run(ctx->{
            assertFalse(ctx.containsBean("rabbitHealthContributor"));
            assertFalse(ctx.getBeansOfType(HealthContributor.class).isEmpty());
            ctx.getBeansOfType(HealthIndicator.class).values().forEach(h->assertEquals(Status.UP,h.health().getStatus()));
            verify(factory,never()).createConnection();
        });
    }
    @Test void explicitOverrideRegistersRabbitAndAttemptsMockTransport() {
        when(factory.createConnection()).thenThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException()));
        context().withPropertyValues("management.health.rabbit.enabled=true").run(ctx->{
            assertTrue(ctx.containsBean("rabbitHealthContributor"));
            assertEquals(Status.DOWN,ctx.getBean("rabbitHealthContributor",HealthIndicator.class).health().getStatus());
            verify(factory).createConnection();
        });
    }
}
