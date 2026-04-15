package com.chao.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        ServerBearerTokenAuthenticationConverter converter = new ServerBearerTokenAuthenticationConverter();
        converter.setAllowUriQueryParameter(true);

        return http
                // 1. 启用CORS（重要！）
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // 2. 禁用CSRF
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // 3. 授权规则
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/api/auth/**", "/actuator/**").permitAll()
                        .anyExchange().authenticated()
                )
                // 4. OAuth2资源服务器配置
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenConverter(converter)
                        .jwt(jwt -> {})
                )
                .build();
    }

    /**
     * CORS配置 - 支持本地和外网访问
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // 允许的来源（支持本地和外网）
        configuration.setAllowedOriginPatterns(Arrays.asList(
                "*"  // 允许所有来源（最简单，适合演示）
                // 或者明确列出：
                // "http://localhost:*",
                // "http://127.0.0.1:*",
                // "https://*.cpolar.cn",
                // "http://*.cpolar.cn"
        ));

        // 允许的HTTP方法
        configuration.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
        ));

        // 允许的请求头
        configuration.setAllowedHeaders(Arrays.asList(
                "*"
                // 或者明确列出：
                // "Authorization",
                // "Content-Type",
                // "X-Requested-With",
                // "Accept",
                // "Origin",
                // "Access-Control-Request-Method",
                // "Access-Control-Request-Headers",
                // "X-User-Id",
                // "X-Username",
                // "X-Roles"
        ));

        // 允许的响应头（暴露给前端）
        configuration.setExposedHeaders(Arrays.asList(
                "Authorization",
                "Content-Type"
        ));

        // 是否允许携带凭证
        configuration.setAllowCredentials(true);

        // 预检请求缓存时间（秒）
        configuration.setMaxAge(3600L);

        // 应用到所有路径
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}