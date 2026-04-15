package com.chao.common.dto;

import lombok.Data;

@Data
public class GoalAiTaskMessage {
    private Long userId;
    private Long goalId;
    private String goalDescription;
    private String systemPrompt;
}
