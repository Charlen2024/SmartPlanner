package com.chao.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserJournalDto {
    private Long id;
    private Long userId;
    private Long goalId;
    private String content;
    private String mood;
    private LocalDateTime createdAt;
}

