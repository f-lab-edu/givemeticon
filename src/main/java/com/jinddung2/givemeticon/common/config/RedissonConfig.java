package com.jinddung2.givemeticon.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.coupon.host}")
    private String host;

    @Value("${spring.data.redis.coupon.port}")
    private String port;

    @Bean
    public RedissonClient redisClient() {
        RedissonClient redisClient;
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port);
        redisClient = Redisson.create(config);
        return redisClient;
    }
}
