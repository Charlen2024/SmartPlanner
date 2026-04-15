package com.chao.common.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class DailyPlanJobStartRequest {
    private LocalDate date;
    private String mode;
}

