package com.jinddung2.givemeticon.harness;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * stock(Redisson+MySQL) 격리 runner 가 명령행 인자/환경변수로 덮어쓰는 값이 실제 유효 설정에서 이기는지 정적으로 확인한다.
 * 인프라(DB/Redis/Kafka)에 접속하지 않는다. withPropertyValues 는 설정 파일보다 우선하는 속성 소스이며 명령행 인자와 같은 역할이다.
 */
class VerifierStockConfigurationTest {

    private static final String[] PROFILES = {"spring.profiles.active=verifier-loadtest,mysql-loadtest,redis-lock-loadtest"};

    private ApplicationContextRunner runner() {
        Path config = Path.of("scripts/verifier-isolated/verifier-loadtest-config.yml").toAbsolutePath();
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(PROFILES)
                .withPropertyValues("spring.config.additional-location=" + config.toUri());
    }

    @Test
    void isolatedOverridesWinOverSharedInfrastructureDefaults() {
        runner().withPropertyValues(
                "LOADTEST_DB_URL=jdbc:mysql://verifier-cap-mysql:3306/givemeticon_loadtest_cap",
                "LOADTEST_DB_USER=root", "LOADTEST_DB_PASSWORD=dummy", "LOADTEST_HIKARI_MAX=20",
                "COUPON_ISSUE_WORKER_MODE=off",
                "spring.data.redis.mail.host=verifier-cap-redis-mail", "spring.data.redis.mail.port=6379",
                "spring.data.redis.coupon.host=verifier-cap-redis-coupon", "spring.data.redis.coupon.port=6379",
                "bootstrap.server=localhost:19092").run(context -> {
            assertThat(context).hasNotFailed();
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:mysql://verifier-cap-mysql:3306/givemeticon_loadtest_cap");
            assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("20");
            assertThat(env.getProperty("spring.data.redis.mail.host")).isEqualTo("verifier-cap-redis-mail");
            assertThat(env.getProperty("spring.data.redis.coupon.host")).isEqualTo("verifier-cap-redis-coupon");
            assertThat(env.getProperty("bootstrap.server")).isEqualTo("localhost:19092");
            assertThat(env.getProperty("coupon.distributed-lock.enabled", Boolean.class)).isTrue();
            assertThat(env.getProperty("coupon.issue-worker.mode")).isEqualTo("off");
        });
    }

    @Test
    void mysqlLoadtestProfileAloneDefaultsPointAtSharedInfrastructure() {
        // 음성 대조: 격리 설정/덮어쓰기 없이 mysql-loadtest 프로필만 쓰면 공유 Redis/Kafka/3306 을 가리킨다.
        // -> runner 의 격리 설정 파일 + 명령행 덮어쓰기가 필수임을 증명한다.
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=mysql-loadtest", "LOADTEST_DB_PASSWORD=dummy")
                .run(context -> {
                    var env = context.getEnvironment();
                    assertThat(env.getProperty("spring.data.redis.mail.port")).isEqualTo("6379");
                    assertThat(env.getProperty("spring.data.redis.coupon.port")).isEqualTo("6380");
                    assertThat(env.getProperty("bootstrap.server")).isEqualTo("localhost:9092");
                    assertThat(env.getProperty("spring.datasource.url")).contains("localhost:3306");
                });
    }
}
