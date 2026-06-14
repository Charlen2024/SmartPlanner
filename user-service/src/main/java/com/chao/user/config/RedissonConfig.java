package com.chao.user.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(Environment environment) {
        String host = environment.getProperty("spring.redis.host");
        if (host == null || host.isBlank()) {
            host = environment.getProperty("spring.data.redis.host");
        }
        if (host == null || host.isBlank()) {
            host = environment.getProperty("SPRING_REDIS_HOST");
        }
        if (host == null || host.isBlank()) {
            host = "localhost";
        }

        Integer port = environment.getProperty("spring.redis.port", Integer.class);
        if (port == null) {
            port = environment.getProperty("spring.data.redis.port", Integer.class);
        }
        if (port == null) {
            port = environment.getProperty("SPRING_REDIS_PORT", Integer.class);
        }
        if (port == null || port <= 0) {
            port = 6379;
        }

        String password = environment.getProperty("spring.redis.password");
        if (password == null || password.isBlank()) {
            password = environment.getProperty("spring.data.redis.password");
        }

        Config config = new Config();
        String address = "redis://" + host + ":" + port;
        var single = config.useSingleServer().setAddress(address);
        if (password != null && !password.isBlank()) {
            single.setPassword(password);
        }
        return Redisson.create(config);
    }
}
