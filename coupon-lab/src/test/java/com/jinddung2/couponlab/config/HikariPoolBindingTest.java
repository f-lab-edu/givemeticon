package com.jinddung2.couponlab.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * docker-compose.load.yml sets the connection pool size through an environment variable (verify.sh load --pool N).
 * Spring Boot binds the hikari pool from "spring.datasource.hikari.maximum-pool-size"; this pins which variable name
 * reaches that property, with no database.
 */
class HikariPoolBindingTest {

    private static Binder binderFor(String variable, String value) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("testEnvironment", Map.<String, Object>of(variable, value)));
        return Binder.get(environment);
    }

    @Test
    void springDatasourceHikariMaximumPoolSizeReachesTheHikariProperty() {
        Binder binder = binderFor("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "5");

        assertThat(binder.bind("spring.datasource.hikari.maximum-pool-size", Integer.class).get()).isEqualTo(5);
    }

    @Test
    void theShortNameDoesNotBind() {
        Binder binder = binderFor("HIKARI_MAXIMUM_POOL_SIZE", "5");

        assertThat(binder.bind("spring.datasource.hikari.maximum-pool-size", Integer.class).isBound()).isFalse();
    }
}
