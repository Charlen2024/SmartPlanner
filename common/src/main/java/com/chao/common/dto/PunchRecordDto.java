package com.chao.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PunchRecordDto {
    private Long id;
    private Long userId;
    private Long taskId;
    private Integer punchType;
    private String locationInfo;
    private String evidenceUrl;
    private Integer aiAuditResult;
    private String aiAuditRemark;
    private Integer durationSeconds;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String taskTitle;
    private LocalDateTime createdAt;
}
