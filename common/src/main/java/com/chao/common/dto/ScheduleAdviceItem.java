package com.chao.common.dto;

import lombok.Data;

@Data
public class ScheduleAdviceItem {
    private Long taskId;
    private String title;
    private String description;
    private String startTime;
    private String endTime;
    private Integer timeBudgetMinutes;
}
