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
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class InfoController {
    private final ObjectMapper objectMapper;
    private final WeatherClient weatherClient;
    private final PortraitComputeService portraitComputeService;
    private final ObjectProvider<RedissonClient> redissonProvider;

    private record WeatherLoc(Double lat, Double lon, String name) {
        boolean hasCoords() { return lat != null && lon != null; }
    }

    @GetMapping("/weather")
    public Result<WeatherDto> weather(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon,
            @RequestParam(required = false) String location,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt != null ? JwtUtils.getUserId(jwt) : null;
        boolean useCoords = lat != null && lon != null;

        // If browser provided coordinates, cache them to Redis (first time / update)
        if (useCoords && userId != null) {
            trySaveCoords(userId, lat, lon, null);
        }

        // Try to use Redis-cached coordinates even when only location name is provided
        WeatherLoc savedLoc = getUserWeatherLocation(userId);
        if (!useCoords && savedLoc.hasCoords()) {
            lat = savedLoc.lat();
            lon = savedLoc.lon();
            useCoords = true;
        }
        if (!useCoords && !savedLoc.name().isBlank()) {
            location = savedLoc.name();
        }

        String cacheKey = useCoords
                ? String.format("sp:weather:coord:%.4f,%.4f", lat, lon)
                : "sp:weather:data:" + resolveLoc(location, jwt);

        RedissonClient r = redissonProvider.getIfAvailable();
        if (r != null) {
            try {
                String cached = String.valueOf(r.getBucket(cacheKey).get());
                if (cached != null && !"null".equals(cached)) {
                    WeatherDto dto = objectMapper.readValue(cached, WeatherDto.class);
                    if (dto.getDate() != null && dto.getDate().equals(LocalDate.now().toString())) {
                        return Result.success(dto);
                    }
                }
            } catch (Exception e) {
                log.debug("Weather cache read failed: {}", e.toString());
            }
        }

        WeatherData wd = useCoords
                ? weatherClient.fetch(lat, lon)
                : weatherClient.fetch(resolveLoc(location, jwt));

        WeatherDto dto = new WeatherDto();
        dto.setDate(LocalDate.now().toString());
        if (useCoords) {
            String savedName = savedLoc.name();
            dto.setLocation(!savedName.isBlank() ? savedName : wd.getLocation());
        } else {
            dto.setLocation(wd.getLocation());
        }
        dto.setTemperature(wd.getTemperature());
        dto.setFeelsLike(wd.getFeelsLike());
        dto.setWindspeed(wd.getWindspeed());
        dto.setHumidity(wd.getHumidity());
        dto.setSummary(wd.getWeatherDescCn() != null ? wd.getWeatherDescCn() : "天气服务不可用");

        if (r != null) {
            try {
                String json = objectMapper.writeValueAsString(dto);
                r.getBucket(cacheKey).set(json, 30, java.util.concurrent.TimeUnit.MINUTES);
            } catch (Exception e) {
                log.debug("Weather cache write failed: {}", e.toString());
            }
        }
        return Result.success(dto);
    }

    private String resolveLoc(String location, Jwt jwt) {
        if (location != null && !location.isBlank()) return location.trim();
        String saved = getUserWeatherLocation(jwt);
        return !saved.isBlank() ? saved : "Shenzhen";
    }

    @PutMapping("/weather-location")
    public Result<String> saveWeatherLocation(@AuthenticationPrincipal Jwt jwt,
                                              @RequestParam(required = false) String location,
                                              @RequestParam(required = false) Double lat,
                                              @RequestParam(required = false) Double lon) {
        Long userId = JwtUtils.getUserId(jwt);
        String loc = (location != null && !location.isBlank()) ? location.trim() : "Shenzhen";
        trySaveCoords(userId, lat, lon, loc);
        return Result.success(loc);
    }

    private void trySaveCoords(Long userId, Double lat, Double lon, String name) {
        if (userId == null) return;
        try {
            RedissonClient r = redissonProvider.getIfAvailable();
            if (r == null) return;
            String key = "sp:weather:loc:" + userId;

            // Read existing data
            String existing = String.valueOf(r.getBucket(key).get());
            Double existingLat = null, existingLon = null;
            String existingName = "";
            if (existing != null && !"null".equals(existing)) {
                if (existing.startsWith("{")) {
                    Map m = objectMapper.readValue(existing, Map.class);
                    existingLat = toDouble(m.get("lat"));
                    existingLon = toDouble(m.get("lon"));
                    existingName = String.valueOf(m.getOrDefault("name", ""));
                } else {
                    existingName = existing.trim();
                }
            }

            // Only update coordinates when explicitly provided (not null)
            Double newLat = lat != null ? lat : existingLat;
            Double newLon = lon != null ? lon : existingLon;
            String newName = name != null && !name.isBlank() ? name.trim()
                    : (!existingName.isBlank() ? existingName : "Shenzhen");

            Map<String, Object> data = new java.util.LinkedHashMap<>();
            if (newLat != null) data.put("lat", newLat);
            if (newLon != null) data.put("lon", newLon);
            data.put("name", newName);

            String json = objectMapper.writeValueAsString(data);
            r.getBucket(key).set(json, 365, java.util.concurrent.TimeUnit.DAYS);
        } catch (Exception ignored) {
            log.debug("Failed to save weather coords for user {}", userId);
        }
    }

    private Double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private WeatherLoc getUserWeatherLocation(Long userId) {
        try {
            if (userId == null) return new WeatherLoc(null, null, "");
            RedissonClient r = redissonProvider.getIfAvailable();
            if (r == null) return new WeatherLoc(null, null, "");
            String raw = String.valueOf(r.getBucket("sp:weather:loc:" + userId).get());
            if (raw == null || "null".equals(raw)) return new WeatherLoc(null, null, "");
            if (raw.startsWith("{")) {
                Map m = objectMapper.readValue(raw, Map.class);
                Double lat = toDouble(m.get("lat"));
                Double lon = toDouble(m.get("lon"));
                String name = String.valueOf(m.getOrDefault("name", ""));
                return new WeatherLoc(lat, lon, "null".equals(name) ? "" : name.trim());
            }
            // Legacy: plain city name string
            return new WeatherLoc(null, null, raw.trim());
        } catch (Exception ignored) {
            return new WeatherLoc(null, null, "");
        }
    }

    private String getUserWeatherLocation(Jwt jwt) {
        Long userId = jwt != null ? JwtUtils.getUserId(jwt) : null;
        return getUserWeatherLocation(userId).name();
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
