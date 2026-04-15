package com.chao.user.dto;

import lombok.Data;

@Data
public class WeatherDto {
    private String date;
    private Double temperature;
    private Double windspeed;
    private Integer weatherCode;
    private String summary;
}

