package com.chao.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskScheduleDto {
    private Long id;
    private Long userId;
    private Long taskId;
    private String taskTitle;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;
}
