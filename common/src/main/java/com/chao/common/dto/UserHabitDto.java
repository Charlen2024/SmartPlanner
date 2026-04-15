package com.chao.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserHabitDto {
    private Long id;
    private Long userId;
    private Integer morningPersonScore;
    private Integer focusDurationAvg;
    private Float procrastinationIndex;
    private LocalDateTime lastAnalysisTime;
}

