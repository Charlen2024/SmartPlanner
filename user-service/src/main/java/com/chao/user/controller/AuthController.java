package com.chao.user.controller;

import com.chao.common.dto.Result;
import com.chao.user.dto.AuthLoginRequest;
import com.chao.user.dto.AuthMeResponse;
import com.chao.user.dto.AuthRefreshRequest;
import com.chao.user.dto.AuthRegisterRequest;
import com.chao.user.dto.AuthTokenResponse;
import com.chao.user.entity.AppUser;
import com.chao.user.service.AppUserService;
import com.chao.user.service.JwtTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtTokenService jwtTokenService;
    private final AppUserService appUserService;

    @PostMapping("/register")
    public Result<AuthTokenResponse> register(@RequestBody AuthRegisterRequest request) {
        AppUser created = appUserService.register(request.getUsername(), request.getPassword());
        Authentication authentication = new UsernamePasswordAuthenticationToken(created.getUsername(), "N/A",
                AuthorityUtils.createAuthorityList("USER"));
        return Result.success(jwtTokenService.issueTokens(authentication, created.getId()));
    }

    @PostMapping("/login")
    public Result<AuthTokenResponse> login(@RequestBody AuthLoginRequest request) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        Long userId = appUserService.getUserIdOrThrow(auth.getName());
        return Result.success(jwtTokenService.issueTokens(auth, userId));
    }

    @PostMapping("/refresh")
    public Result<AuthTokenResponse> refresh(@RequestBody AuthRefreshRequest request) {
        Jwt jwt = jwtTokenService.parse(request.getRefreshToken());
        Object typ = jwt.getClaims().get("typ");
        if (typ == null || !"refresh".equals(String.valueOf(typ))) {
            return Result.fail(401, "Invalid refresh token");
        }

        Object userIdClaim = jwt.getClaims().get("userId");
        Long userId = userIdClaim != null ? Long.valueOf(String.valueOf(userIdClaim)) : null;
        List<String> roles = jwt.getClaimAsStringList("roles");
        Authentication authentication = new UsernamePasswordAuthenticationToken(jwt.getSubject(), "N/A",
                roles != null ? AuthorityUtils.createAuthorityList(roles.toArray(new String[0])) : AuthorityUtils.NO_AUTHORITIES);

        AuthTokenResponse tokens = jwtTokenService.issueTokens(authentication, userId);
        return Result.success(tokens);
    }

    @GetMapping("/me")
    public Result<AuthMeResponse> me(@org.springframework.security.core.annotation.AuthenticationPrincipal Jwt jwt) {
        AuthMeResponse resp = new AuthMeResponse();
        Object userIdClaim = jwt.getClaims().get("userId");
        if (userIdClaim != null) {
            resp.setUserId(Long.valueOf(String.valueOf(userIdClaim)));
        }
        resp.setUsername(jwt.getSubject());
        resp.setRoles(jwt.getClaimAsStringList("roles"));
        AppUser u = appUserService.getById(resp.getUserId());
        resp.setScheduleImported(u != null ? Boolean.TRUE.equals(u.getScheduleImported()) : null);
        resp.setFirstWeekMonday(u != null ? u.getFirstWeekMonday() : null);
        return Result.success(resp);
    }
}
