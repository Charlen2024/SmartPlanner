package com.chao.gateway.filter;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class RedisRateLimitFilter implements GlobalFilter, Ordered {
    private final ReactiveStringRedisTemplate redisTemplate;
    private final KeyResolver userKeyResolver;

    @Value("${gateway.ratelimit.burst-capacity:10}")
    private long burstCapacity;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith("/api/") || path.startsWith("/api/auth/") || path.startsWith("/actuator/")) {
            return chain.filter(exchange);
        }

        long epochSecond = Instant.now().getEpochSecond();
        return userKeyResolver.resolve(exchange)
                .defaultIfEmpty("unknown")
                .flatMap(key -> {
                    String redisKey = "rl:" + key + ":" + epochSecond;
                    return redisTemplate.opsForValue().increment(redisKey)
                            .flatMap(cnt -> {
                                Mono<Long> afterExpire = cnt != null && cnt == 1
                                        ? redisTemplate.expire(redisKey, Duration.ofSeconds(2)).thenReturn(cnt)
                                        : Mono.just(cnt);
                                return afterExpire.flatMap(c -> {
                                    if (c != null && c <= burstCapacity) {
                                        return chain.filter(exchange);
                                    }
                                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                                    return exchange.getResponse().setComplete();
                                });
                            });
                });
    }

    @Override
    public int getOrder() {
        return -80;
    }
}

