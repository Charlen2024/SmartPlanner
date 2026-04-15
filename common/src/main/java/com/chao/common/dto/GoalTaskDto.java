package com.chao.common.dto;

import lombok.Data;

import java.util.List;

@Data
public class GoalTaskDto {
    private Long id;
    private Long userId;
    private Long goalId;
    private Long parentId;
    private String title;
    private String description;
    private Integer priority;
    private Integer estimatedMinutes;
    private Integer status;
    private List<GoalTaskDto> subTasks;
}

