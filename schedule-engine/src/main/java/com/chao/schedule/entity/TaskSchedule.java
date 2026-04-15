package com.chao.schedule.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("task_schedules")
public class TaskSchedule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long taskId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status; // 0-未开始, 1-已完成, 2-跳过
}
