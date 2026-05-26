package com.chao.common.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class DailyPlanJobStartRequest {
    private LocalDate date;
    private String mode;
    private Long goalId;
    private List<Long> taskIds;
    private Integer days;
}
