package com.chao.common.util;

import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.dto.WeatherData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
public class WeatherClient {
    private final RestTemplate externalRestTemplate;
    private final ObjectProvider<OpenAiCompatClient> aiClientProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentMap<String, String> cityTranslationCache = new ConcurrentHashMap<>();

    public WeatherClient(RestTemplate externalRestTemplate, ObjectProvider<OpenAiCompatClient> aiClientProvider) {
        this.externalRestTemplate = externalRestTemplate;
        this.aiClientProvider = aiClientProvider;
    }

    public WeatherData fetch(String location) {
        String loc = location != null && !location.isBlank() ? location.trim() : "Shenzhen";
        WeatherData data = new WeatherData();
        data.setLocation(loc);

        try {
            String encoded = URLEncoder.encode(loc, StandardCharsets.UTF_8);
            String url = "https://wttr.in/" + encoded + "?format=j1";
            String json = fetchJson(url);

            parseWeatherJson(json, data);
        } catch (Exception e) {
            log.warn("Weather fetch failed for location={}: {}", loc, e.toString());
            data.setWeatherDescCn("天气服务不可用");
        }
        return data;
    }

    public WeatherData fetch(double lat, double lon) {
        WeatherData data = new WeatherData();


        try {
            String url = "https://wttr.in/" + lat + "," + lon + "?format=j1";
            String json = fetchJson(url);

            parseWeatherJson(json, data);

            JsonNode root = objectMapper.readTree(json);
            JsonNode nearest = root.path("nearest_area");
            String resolvedName = null;
            if (nearest.isArray() && !nearest.isEmpty()) {
                JsonNode area = nearest.get(0);
                String city = area.path("areaName").isArray() && !area.path("areaName").isEmpty()
                        ? area.path("areaName").get(0).path("value").asText() : "";
                String region = area.path("region").isArray() && !area.path("region").isEmpty()
                        ? area.path("region").get(0).path("value").asText() : "";
                if (!city.isBlank()) {
                    String rawName = region.isBlank() ? city : city + "," + region;
                    String cn = toChineseCityName(rawName);
                    resolvedName = (cn != null && !cn.isBlank()) ? cn : rawName;
                }
            }
            data.setLocation(resolvedName != null ? resolvedName : String.format("%.4f,%.4f", lat, lon));
        } catch (Exception e) {
            log.warn("Weather fetch failed for coords={},{}: {}", lat, lon, e.toString());
            data.setWeatherDescCn("天气服务不可用");
            data.setLocation(String.format("%.4f,%.4f", lat, lon));
        }
        return data;
    }

    private String toChineseCityName(String en) {
        if (en == null || en.isBlank()) return en;
        // Fast path: already Chinese
        if (en.codePoints().anyMatch(c -> c > 0x2E80)) return en;

        String cached = cityTranslationCache.get(en);
        if (cached != null) return cached;

        try {
            OpenAiCompatClient ai = aiClientProvider != null ? aiClientProvider.getIfAvailable() : null;
            if (ai != null) {
                String result = ai.complete(
                    "You are a translator. Translate the following place name to Chinese. " +
                    "Return ONLY the Chinese name, no explanation, no punctuation, no extra text.",
                    en
                );
                if (result != null && !result.isBlank()) {
                    String cleaned = result.trim().replaceAll("[，。！？、；：（）\"'\\[\\]{}<>《》\\s]+", "");
                    cityTranslationCache.put(en, cleaned);
                    return cleaned;
                }
            }
        } catch (Exception e) {
            log.debug("LLM city translation failed for '{}': {}", en, e.toString());
        }
        return en;
    }

    private String fetchJson(String url) {
        return externalRestTemplate.execute(URI.create(url), HttpMethod.GET, request -> {
            request.getHeaders().set("User-Agent", "SmartPlanner/1.0");
        }, response -> {
            byte[] bytes = response.getBody().readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        });
    }

    private void parseWeatherJson(String json, WeatherData data) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode cc = root.path("current_condition");
            if (cc.isArray() && !cc.isEmpty()) {
                JsonNode c = cc.get(0);
                data.setTemperature(parseDouble(c, "temp_C"));
                data.setFeelsLike(parseDouble(c, "FeelsLikeC"));
                data.setHumidity(parseString(c, "humidity"));
                data.setWindspeed(parseDouble(c, "windspeedKmph"));
                data.setWindDirection(parseString(c, "winddir16Point"));
                data.setVisibility(parseDouble(c, "visibility"));
                data.setPressure(parseDouble(c, "pressure"));
                String desc = c.path("weatherDesc").isArray() && !c.path("weatherDesc").isEmpty()
                        ? c.path("weatherDesc").get(0).path("value").asText() : "";
                data.setWeatherDesc(desc);
                data.setWeatherDescCn(translateDesc(desc));
            }

            JsonNode weather = root.path("weather");
            if (weather.isArray() && !weather.isEmpty()) {
                JsonNode today = weather.get(0);
                data.setMaxTemp(parseDouble(today, "maxtempC"));
                data.setMinTemp(parseDouble(today, "mintempC"));
                data.setSunHour(parseDouble(today, "sunHour"));
            }
        } catch (Exception e) {
            log.warn("Weather JSON parse failed", e);
        }
    }

    public String translateDesc(String desc) {
        if (desc == null || desc.isBlank()) return "未知";
        String d = desc.trim();
        return switch (d) {
            case "Sunny", "Clear" -> "晴";
            case "Partly Cloudy", "Partly cloudy" -> "多云";
            case "Cloudy" -> "阴";
            case "Overcast" -> "阴";
            case "Mist", "Fog", "Freezing fog" -> "雾";
            case "Light drizzle", "Patchy light drizzle" -> "毛毛雨";
            case "Light rain", "Light Rain" -> "小雨";
            case "Moderate rain", "Moderate or heavy rain shower" -> "中雨";
            case "Heavy rain", "Torrential rain shower" -> "大雨";
            case "Patchy rain possible", "Patchy rain nearby" -> "可能有雨";
            case "Thunderstorm", "Thundery outbreaks possible" -> "雷暴";
            case "Light snow", "Patchy light snow" -> "小雪";
            case "Moderate snow" -> "中雪";
            case "Heavy snow" -> "大雪";
            case "Blizzard" -> "暴风雪";
            case "Light sleet" -> "雨夹雪";
            default -> fuzzyMatch(d);
        };
    }

    private String fuzzyMatch(String d) {
        String lower = d.toLowerCase();
        if (lower.contains("sunny") || lower.contains("clear")) return "晴";
        if (lower.contains("cloudy")) return "多云";
        if (lower.contains("overcast")) return "阴";
        if (lower.contains("fog") || lower.contains("mist")) return "雾";
        if (lower.contains("drizzle")) return "毛毛雨";
        if (lower.contains("heavy rain") || lower.contains("torrential")) return "大雨";
        if (lower.contains("rain") || lower.contains("shower")) return "有雨";
        if (lower.contains("thunder") || lower.contains("lightning")) return "雷暴";
        if (lower.contains("snow") || lower.contains("blizzard")) return "雪";
        if (lower.contains("sleet") || lower.contains("ice")) return "雨夹雪";
        if (lower.contains("wind")) return "大风";
        return "未知";
    }

    private Double parseDouble(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (n.isNull() || n.isMissingNode()) return null;
        if (n.isNumber()) return n.asDouble();
        if (n.isTextual()) {
            try { return Double.parseDouble(n.asText().trim()); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private String parseString(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (n.isNull() || n.isMissingNode()) return null;
        return n.asText().trim();
    }
}
