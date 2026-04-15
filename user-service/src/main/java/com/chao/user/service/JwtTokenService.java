package com.chao.user.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.chao.user.dto.AuthTokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class JwtTokenService {
    private final JwtDecoder jwtDecoder;
    private final byte[] jwtSecretBytes;

    @Value("${security.jwt.issuer:http://vibe}")
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

        String accessToken = sign(accessClaims);
        String refreshToken = sign(refreshClaims);

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

    private String sign(JwtClaimsSet claims) {
        try {
            JWSHeader header = new JWSHeader(JWSAlgorithm.HS256);
            String iss = claims.getIssuer() != null ? String.valueOf(claims.getIssuer()) : null;
            JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                    .issuer(iss)
                    .subject(claims.getSubject())
                    .issueTime(claims.getIssuedAt() != null ? java.util.Date.from(claims.getIssuedAt()) : null)
                    .expirationTime(claims.getExpiresAt() != null ? java.util.Date.from(claims.getExpiresAt()) : null);

            claims.getClaims().forEach((k, v) -> {
                if (!"iss".equals(k) && !"sub".equals(k) && !"iat".equals(k) && !"exp".equals(k)) {
                    b.claim(k, v);
                }
            });

            SignedJWT jwt = new SignedJWT(header, b.build());
            JWSSigner signer = new MACSigner(jwtSecretBytes);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT sign failed", e);
        }
    }
}
