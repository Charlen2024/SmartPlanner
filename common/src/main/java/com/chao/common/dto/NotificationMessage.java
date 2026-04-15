package com.chao.common.dto;

import lombok.Data;

@Data
public class NotificationMessage {
    private Long userId;
    private String type;
    private String content;
}
