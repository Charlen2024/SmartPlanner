package com.chao.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collections;
import java.util.List;

public final class JwtUtils {

    private static final Logger log = LoggerFactory.getLogger(JwtUtils.class);

    private JwtUtils() {}

    public static Long getUserId(Jwt jwt) {
        if (jwt == null) return null;
        try {
            Object claim = jwt.getClaim("userId");
            if (claim == null) return null;
            if (claim instanceof Long l) return l;
            if (claim instanceof Integer i) return i.longValue();
            return Long.valueOf(claim.toString());
        } catch (NumberFormatException e) {
            log.warn("Invalid userId claim in JWT: {}", String.valueOf(jwt.getClaim("userId")));
            return null;
        }
    }

    public static String getUsername(Jwt jwt) {
        if (jwt == null) return null;
        return jwt.getSubject();
    }

    public static List<String> getRoles(Jwt jwt) {
        if (jwt == null) return Collections.emptyList();
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null ? roles : Collections.emptyList();
    }

    public static String getStringClaim(Jwt jwt, String name) {
        if (jwt == null) return null;
        Object claim = jwt.getClaim(name);
        return claim != null ? claim.toString() : null;
    }
}
