package com.chao.common.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class PlanCandidateDto {
    private Long id;
    private Long userId;
    private LocalDate planDate;
    private Integer status;
    private String note;
    private List<FreeSlotDto> freeSlots;
    private List<FreeSlotDto> suggestedFreeSlots;
    private List<TaskScheduleDto> schedules;
    private List<TaskScheduleDto> suggestedSchedules;
    private LocalDateTime createdAt;
}
