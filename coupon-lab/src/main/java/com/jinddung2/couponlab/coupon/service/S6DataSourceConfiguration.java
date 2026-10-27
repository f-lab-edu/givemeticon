package com.jinddung2.couponlab.coupon.service;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

/**
 * S6 only: Hikari waits 2 s, not its default 30 s, for a connection. The Consumer learns that the database is gone from
 * that wait failing, so the default would hold a request for 30 s before the Consumer pauses. Applied before the pool starts.
 */
@Configuration
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "s6")
public class S6DataSourceConfiguration {

    static final long CONNECTION_TIMEOUT_MS = 2000;

    @Bean
    static BeanPostProcessor s6ConnectionTimeout() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource && bean instanceof HikariDataSource hikari) {
                    hikari.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
                }
                return bean;
            }
        };
    }
}
