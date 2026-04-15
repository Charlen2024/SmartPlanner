package com.chao.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GoalDto {
    private Long id;
    private Long userId;
    private String title;
    private String description;
    private Integer status;
    private LocalDateTime deadline;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

