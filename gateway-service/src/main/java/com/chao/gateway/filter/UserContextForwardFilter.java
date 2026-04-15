package com.chao.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

@Component
public class UserContextForwardFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .cast(Authentication.class)
                .flatMap(auth -> {
                    if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
                        return chain.filter(exchange);
                    }
                    Object userId = jwtAuth.getTokenAttributes().get("userId");
                    String username = jwtAuth.getName();
                    String roles = jwtAuth.getAuthorities().stream().map(a -> a.getAuthority()).collect(Collectors.joining(","));

                    ServerWebExchange mutated = exchange.mutate()
                            .request(req -> {
                                if (userId != null) {
                                    req.header("X-User-Id", String.valueOf(userId));
                                }
                                req.header("X-Username", username);
                                req.header("X-Roles", roles);
                            })
                            .build();
                    return chain.filter(mutated);
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        return -90;
    }
}

