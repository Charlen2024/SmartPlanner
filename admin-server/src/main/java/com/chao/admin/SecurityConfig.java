package com.chao.admin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .formLogin(form -> form
                        .authenticationSuccessHandler(new RedirectServerAuthenticationSuccessHandler("/")))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(
                                "/login", "/error",
                                "/assets/**", "/variables.css",
                                "/actuator/**"
                        ).permitAll()
                        .anyExchange().authenticated())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .build();
    }
}
