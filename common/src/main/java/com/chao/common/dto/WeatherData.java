package com.chao.common.dto;

import lombok.Data;

@Data
public class WeatherData {
    private String location;
    private Double temperature;
    private Double feelsLike;
    private Double windspeed;
    private String humidity;
    private String weatherDesc;
    private String weatherDescCn;
    private String windDirection;
    private Double visibility;
    private Double pressure;
    private Double maxTemp;
    private Double minTemp;
    private Double sunHour;
}
