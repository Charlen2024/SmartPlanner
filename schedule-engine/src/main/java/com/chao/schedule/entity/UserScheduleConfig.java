package com.chao.schedule.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

@Data
@TableName("user_schedule_config")
public class UserScheduleConfig {
    @TableId(type = IdType.INPUT)
    private Long userId;
    private LocalDate firstWeekMonday;
}
