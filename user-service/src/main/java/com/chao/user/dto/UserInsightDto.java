package com.chao.user.dto;

import lombok.Data;

import java.util.List;

@Data
public class UserInsightDto {
    private Double onTimeRate;
    private Double avgDelayMinutes;
    private Integer matchedPunchCount;
    private Double completionRate;
    private Integer streak;
    private List<String> tips;
}
