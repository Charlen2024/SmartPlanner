package com.chao.common.dto;

import com.chao.common.client.ResourceClient;
import lombok.Data;

@Data
public class ResourceAdviceJobStatusResponse {
    private String jobId;
    private String status;
    private String stage;
    private Integer progress;
    private String message;
    private String error;
    private ResourceClient.ResourceAdviceResponse result;
}
