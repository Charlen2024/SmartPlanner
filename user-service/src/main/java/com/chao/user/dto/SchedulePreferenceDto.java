package com.chao.user.dto;

import lombok.Data;

@Data
public class SchedulePreferenceDto {
    private Integer focusMinutes;
    private Integer breakMinutes;
    private Integer maxDailyMinutes;
}

