package com.chao.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .cast(Authentication.class)
                .flatMap(auth -> {
                    if (auth instanceof JwtAuthenticationToken jwtAuth) {
                        Object userId = jwtAuth.getTokenAttributes().get("userId");
                        if (userId != null) {
                            return Mono.just("u:" + userId);
                        }
                    }
                    return Mono.empty();
                })
                .switchIfEmpty(Mono.defer(() -> {
                    String ip = exchange.getRequest().getRemoteAddress() != null
                            ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                            : "unknown";
                    return Mono.just("ip:" + ip);
                }));
    }
}
