package com.chao.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

public class WeatherClientTest {

    private final WeatherClient client = new WeatherClient(new RestTemplate());

    @Test
    void translateDesc_exactMatches() {
        assertEquals("晴", client.translateDesc("Sunny"));
        assertEquals("晴", client.translateDesc("Clear"));
        assertEquals("多云", client.translateDesc("Partly Cloudy"));
        assertEquals("多云", client.translateDesc("Partly cloudy"));
        assertEquals("阴", client.translateDesc("Cloudy"));
        assertEquals("阴", client.translateDesc("Overcast"));
        assertEquals("雾", client.translateDesc("Mist"));
        assertEquals("雾", client.translateDesc("Fog"));
        assertEquals("雾", client.translateDesc("Freezing fog"));
        assertEquals("毛毛雨", client.translateDesc("Light drizzle"));
        assertEquals("毛毛雨", client.translateDesc("Patchy light drizzle"));
        assertEquals("小雨", client.translateDesc("Light rain"));
        assertEquals("中雨", client.translateDesc("Moderate rain"));
        assertEquals("大雨", client.translateDesc("Heavy rain"));
        assertEquals("雷暴", client.translateDesc("Thunderstorm"));
        assertEquals("小雪", client.translateDesc("Light snow"));
        assertEquals("中雪", client.translateDesc("Moderate snow"));
        assertEquals("大雪", client.translateDesc("Heavy snow"));
        assertEquals("暴风雪", client.translateDesc("Blizzard"));
        assertEquals("雨夹雪", client.translateDesc("Light sleet"));
    }

    @Test
    void translateDesc_fuzzyMatches() {
        assertTrue(client.translateDesc("Sunny with clouds").contains("晴"));
        assertTrue(client.translateDesc("Partly cloudy sky").contains("多云"));
        assertTrue(client.translateDesc("Heavy rain shower").contains("大雨"));
        assertTrue(client.translateDesc("Thunder and lightning").contains("雷暴"));
        assertTrue(client.translateDesc("Strong wind").contains("大风"));
    }

    @Test
    void translateDesc_nullOrBlank() {
        assertEquals("未知", client.translateDesc(null));
        assertEquals("未知", client.translateDesc(""));
        assertEquals("未知", client.translateDesc("   "));
    }
}
