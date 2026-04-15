package com.chao.common.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class DailyPlanCommitResponse {
    private LocalDate date;
    private String mode;
    private String note;
    private List<FreeSlotDto> freeSlots;
    private List<TaskScheduleDto> schedules;
}

