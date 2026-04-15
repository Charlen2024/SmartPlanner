package com.chao.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.dto.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter implements GlobalFilter, Ordered {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gateway.auth.api-key:}")
    private String expectedApiKey;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        if (expectedApiKey == null || expectedApiKey.isBlank()) {
            return chain.filter(exchange);
        }

        String provided = exchange.getRequest().getHeaders().getFirst("X-API-KEY");
        if (provided == null || provided.isBlank() || !provided.equals(expectedApiKey)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            try {
                byte[] bytes = objectMapper.writeValueAsBytes(Result.fail(401, "Unauthorized"));
                return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
            } catch (Exception e) {
                byte[] bytes = "{\"code\":401,\"message\":\"Unauthorized\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
                return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
            }
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
