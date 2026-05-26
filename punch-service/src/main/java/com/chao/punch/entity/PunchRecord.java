package com.chao.punch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("punch_records")
public class PunchRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long taskId;
    private Integer punchType; // 1-GPS, 2-截图, 3-问答
    private String locationInfo;
    private String evidenceUrl;
    private Integer aiAuditResult; // 0-待审核, 1-通过, 2-驳回
    private String aiAuditRemark;
    @TableField("duration_seconds")
    private Integer durationSeconds;
    @TableField("started_at")
    private LocalDateTime startedAt;
    @TableField("ended_at")
    private LocalDateTime endedAt;
    @TableField("task_title")
    private String taskTitle;
    private LocalDateTime createdAt;
}
