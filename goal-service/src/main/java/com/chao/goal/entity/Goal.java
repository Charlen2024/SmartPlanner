package com.chao.goal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("goals")
public class Goal {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String description;
    private Integer status; // 0-进行中, 1-已归档
    private LocalDateTime deadline;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
