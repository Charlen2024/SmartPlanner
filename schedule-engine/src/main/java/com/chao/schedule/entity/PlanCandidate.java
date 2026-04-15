package com.chao.schedule.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("plan_candidates")
public class PlanCandidate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate planDate;
    private Integer status;
    private String note;
    private String freeSlotsJson;
    private String suggestedFreeSlotsJson;
    private String schedulesJson;
    private String suggestedSchedulesJson;
    private LocalDateTime createdAt;
}
