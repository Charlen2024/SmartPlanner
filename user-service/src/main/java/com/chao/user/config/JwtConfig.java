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

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
public class JwtConfig {

    @Bean
    public byte[] jwtSecretBytes(@Value("${JWT_SECRET:}") String secret) {
        return secret.getBytes(StandardCharsets.UTF_8);
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
