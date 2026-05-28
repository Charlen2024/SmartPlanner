package com.chao.common.dto;

import lombok.Data;

@Data
public class SchedulePreferenceDto {
    private Integer focusMinutes;
    private Integer breakMinutes;
    private Integer maxDailyMinutes;
    private Float procrastinationIndex;
}
