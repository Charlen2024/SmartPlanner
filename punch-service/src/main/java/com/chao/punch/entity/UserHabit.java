package com.chao.punch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_habits")
public class UserHabit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Integer morningPersonScore;
    private Integer focusDurationAvg;
    private Float procrastinationIndex;
    private LocalDateTime lastAnalysisTime;
}
