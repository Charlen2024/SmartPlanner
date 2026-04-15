package com.chao.goal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("goal_tasks")
public class GoalTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long goalId;
    private Long parentId;
    private String title;
    private String description;
    private Integer priority;
    private Integer status;
    private Integer estimatedMinutes;
    private LocalDateTime deadline;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
