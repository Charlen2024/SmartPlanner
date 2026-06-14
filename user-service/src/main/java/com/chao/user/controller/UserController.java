package com.chao.user.controller;

import com.chao.common.dto.*;
import com.chao.user.dto.DashboardDto;
import com.chao.user.dto.TaskResourcesRequest;
import com.chao.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final com.chao.user.service.AppUserService appUserService;
    private final UserControllerSupport support;

    @GetMapping("/dashboard")
    public Result<DashboardDto> dashboard(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String topic) {
        return Result.success(userService.getDashboard(support.resolveUserId(jwt, headerUserId, userId), date, topic));
    }

    @GetMapping("/internal/users/ids")
    public Result<List<Long>> getAllUserIds() {
        return Result.success(appUserService.listAllUserIds());
    }
}
