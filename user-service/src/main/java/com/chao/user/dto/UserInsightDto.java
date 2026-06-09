package com.chao.user.dto;

import lombok.Data;

import java.util.List;

@Data
public class UserInsightDto {
    private Double onTimeRate;
    private Double avgDelayMinutes;
    private Integer matchedPunchCount;
    private Integer onTimeCount;
    private Integer lateCount;
    private Long totalDelayMinutes;
    private Integer totalSchedules;
    private Integer doneCount;
    private Double completionRate;
    private Integer streak;
    private List<String> tips;
}
