package com.chao.user.service;

import com.chao.user.dto.AuthTokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class JwtTokenService {
    private final JwtDecoder jwtDecoder;
    private final JwtEncoder jwtEncoder;

    @Value("${security.jwt.issuer:http://sp}")
    private String issuer;

    @Value("${security.jwt.access-ttl-seconds:3600}")
    private long accessTtlSeconds;

    @Value("${security.jwt.refresh-ttl-seconds:604800}")
    private long refreshTtlSeconds;

    public AuthTokenResponse issueTokens(Authentication authentication, Long userId) {
        List<String> roles = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        Instant now = Instant.now();

        JwtClaimsSet accessClaims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(accessTtlSeconds))
                .subject(authentication.getName())
                .claim("typ", "access")
                .claim("userId", userId)
                .claim("roles", roles)
                .build();

        JwtClaimsSet refreshClaims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(refreshTtlSeconds))
                .subject(authentication.getName())
                .claim("typ", "refresh")
                .claim("userId", userId)
                .claim("roles", roles)
                .build();

        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, accessClaims)).getTokenValue();
        String refreshToken = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, refreshClaims)).getTokenValue();

        AuthTokenResponse resp = new AuthTokenResponse();
        resp.setTokenType("Bearer");
        resp.setAccessToken(accessToken);
        resp.setRefreshToken(refreshToken);
        resp.setExpiresInSeconds(accessTtlSeconds);
        return resp;
    }

    public Jwt parse(String tokenValue) {
        return jwtDecoder.decode(tokenValue);
    }
}
