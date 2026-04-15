package com.chao.user.dto;

import lombok.Data;

@Data
public class GoalProgressDto {
    private Long goalId;
    private String title;
    private Integer totalTasks;
    private Integer doneTasks;
    private Integer percent;
}

