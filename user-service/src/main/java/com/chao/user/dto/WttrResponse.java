package com.chao.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WttrResponse {
    @JsonProperty("current_condition")
    private List<CurrentCondition> currentCondition;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CurrentCondition {
        @JsonProperty("temp_C")
        private String tempC;
        @JsonProperty("FeelsLikeC")
        private String feelsLikeC;
        @JsonProperty("windspeedKmph")
        private String windspeedKmph;
        private String humidity;
        @JsonProperty("weatherDesc")
        private List<WeatherDesc> weatherDesc;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WeatherDesc {
        private String value;
    }
}
