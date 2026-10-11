package com.jinddung2.givemeticon.harness;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VerifierHarnessConfigurationTest {

    @Test
    void loadsTrackedDummyConfigurationWithoutLocalSubmoduleOrInfrastructure() {
        Path config = Path.of("scripts/verifier-isolated/verifier-loadtest-config.yml").toAbsolutePath();
        assertThat(config).exists();
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.config.additional-location=" + config.toUri(),
                        "spring.profiles.active=verifier-loadtest",
                        "VERIFIER_REDIS_MAIL_HOST=127.0.0.1",
                        "VERIFIER_REDIS_COUPON_HOST=127.0.0.1",
                        "VERIFIER_REDIS_MAIL_PORT=26379",
                        "VERIFIER_REDIS_COUPON_PORT=26380",
                        "VERIFIER_KAFKA_DUMMY_HOST=127.0.0.1",
                        "VERIFIER_KAFKA_DUMMY_PORT=29092")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("spring.data.redis.mail.host")).isEqualTo("127.0.0.1");
                    assertThat(environment.getProperty("spring.data.redis.mail.port")).isEqualTo("26379");
                    assertThat(environment.getProperty("spring.data.redis.coupon.port")).isEqualTo("26380");
                    assertThat(environment.getProperty("bootstrap.server")).isEqualTo("127.0.0.1:29092");
                    assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
                    assertThat(environment.getProperty("management.health.mail.enabled", Boolean.class)).isFalse();
                    assertThat(environment.getProperty("oauth.naver.client-secret")).isEqualTo("loadtest");
                });
    }
}
