package com.chao.user.util;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collections;
import java.util.List;

public final class JwtUtils {

    private JwtUtils() {}

    public static Long getUserId(Jwt jwt) {
        Object claim = jwt.getClaim("userId");
        if (claim == null) return null;
        if (claim instanceof Long l) return l;
        if (claim instanceof Integer i) return i.longValue();
        return Long.valueOf(claim.toString());
    }

    public static String getUsername(Jwt jwt) {
        return jwt.getSubject();
    }

    public static List<String> getRoles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null ? roles : Collections.emptyList();
    }

    public static String getStringClaim(Jwt jwt, String name) {
        Object claim = jwt.getClaim(name);
        return claim != null ? claim.toString() : null;
    }
}
