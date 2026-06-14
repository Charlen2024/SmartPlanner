package com.chao.user.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
public class JwtConfig {

    @Value("${JWT_SECRET:}")
    private String jwtSecret;

    @PostConstruct
    void validateJwtSecret() {
        if (jwtSecret == null || jwtSecret.isBlank() || jwtSecret.length() < 32) {
            log.error("JWT_SECRET is empty or too short (min 32 chars). Set JWT_SECRET env var.");
            throw new IllegalStateException("JWT_SECRET must be at least 32 characters");
        }
    }

    @Bean
    public byte[] jwtSecretBytes() {
        return jwtSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Bean
    public JwtEncoder jwtEncoder(byte[] jwtSecretBytes) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretBytes));
    }

    @Bean
    public JwtDecoder jwtDecoder(byte[] jwtSecretBytes) {
        SecretKey key = new SecretKeySpec(jwtSecretBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
