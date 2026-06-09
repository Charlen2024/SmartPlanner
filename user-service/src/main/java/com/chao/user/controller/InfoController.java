package com.chao.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.dto.Result;
import com.chao.common.dto.WeatherData;
import com.chao.common.util.WeatherClient;
import lombok.extern.slf4j.Slf4j;
import com.chao.user.dto.UserInsightDto;
import com.chao.user.dto.UserPortraitDto;
import com.chao.user.dto.WeatherDto;
import com.chao.user.service.PortraitComputeService;
import com.chao.user.util.JwtUtils;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class InfoController {
    private final ObjectMapper objectMapper;
    private final WeatherClient weatherClient;
    private final PortraitComputeService portraitComputeService;
    private final ObjectProvider<RedissonClient> redissonProvider;

    @GetMapping("/weather")
    public Result<WeatherDto> weather(
            @RequestParam(required = false) String location,
            @AuthenticationPrincipal Jwt jwt) {
        String loc = (location != null && !location.isBlank()) ? location.trim() : getUserWeatherLocation(jwt);
        if (loc.isBlank()) loc = "Shenzhen";
        WeatherData wd = weatherClient.fetch(loc);
        WeatherDto dto = new WeatherDto();
        dto.setDate(LocalDate.now().toString());
        dto.setLocation(loc);
        dto.setTemperature(wd.getTemperature());
        dto.setFeelsLike(wd.getFeelsLike());
        dto.setWindspeed(wd.getWindspeed());
        dto.setHumidity(wd.getHumidity());
        dto.setSummary(wd.getWeatherDescCn() != null ? wd.getWeatherDescCn() : "天气服务不可用");
        return Result.success(dto);
    }

    @PutMapping("/weather-location")
    public Result<String> saveWeatherLocation(@AuthenticationPrincipal Jwt jwt, @RequestParam String location) {
        Long userId = JwtUtils.getUserId(jwt);
        String loc = (location != null && !location.isBlank()) ? location.trim() : "Shenzhen";
        try {
            RedissonClient r = redissonProvider.getIfAvailable();
            if (r != null) {
                r.getBucket("sp:weather:loc:" + userId).set(loc, 365, java.util.concurrent.TimeUnit.DAYS);
            }
        } catch (Exception ignored) {}
        return Result.success(loc);
    }

    private String getUserWeatherLocation(Jwt jwt) {
        try {
            Long userId = jwt != null ? JwtUtils.getUserId(jwt) : null;
            if (userId == null) return "";
            RedissonClient r = redissonProvider.getIfAvailable();
            if (r != null) {
                String loc = String.valueOf(r.getBucket("sp:weather:loc:" + userId).get());
                return loc != null && !"null".equals(loc) ? loc.trim() : "";
            }
        } catch (Exception ignored) {}
        return "";
    }

    @GetMapping("/insights")
    public Result<UserInsightDto> insights(@AuthenticationPrincipal Jwt jwt) {
        Long userId = JwtUtils.getUserId(jwt);
        return Result.success(portraitComputeService.load(userId).getInsights());
    }

    @GetMapping("/portrait")
    public Result<UserPortraitDto> portrait(@AuthenticationPrincipal Jwt jwt) {
        Long userId = JwtUtils.getUserId(jwt);
        return Result.success(portraitComputeService.load(userId));
    }

    @PostMapping("/portrait/recompute")
    public Result<UserPortraitDto> recomputePortrait(@AuthenticationPrincipal Jwt jwt) {
        Long userId = JwtUtils.getUserId(jwt);
        return Result.success(portraitComputeService.recompute(userId));
    }

}
