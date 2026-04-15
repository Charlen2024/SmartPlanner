package com.chao.common.dto;

import lombok.Data;

@Data
public class DailyPlanJobStatusResponse {
    private String jobId;
    private String status;
    private String stage;
    private Integer progress;
    private String message;
    private String error;
    private DailyPlanCommitResponse result;
}

