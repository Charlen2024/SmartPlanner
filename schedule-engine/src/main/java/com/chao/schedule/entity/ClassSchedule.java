package com.chao.schedule.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalTime;

@Data
@TableName("class_schedule")
public class ClassSchedule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String courseName;
    private Integer dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
    private String location;
    private String semester;
}
