package com.chao.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CourseResourceDto {
    private Long id;
    private String topic;
    private String title;
    private String sourceUrl;
    private String platform;
    private String contentSummary;
    private String vectorId;
    private LocalDateTime createdAt;
}

