package com.chao.common.dto;

import lombok.Data;

@Data
public class ResourceAdviceJobMessage {
    private String jobId;
    private Long userId;
    private String topic;
}
