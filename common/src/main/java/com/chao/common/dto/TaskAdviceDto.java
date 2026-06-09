package com.chao.common.dto;

import lombok.Data;

@Data
public class TaskAdviceDto {
    private String start;
    private String done;

    public TaskAdviceDto() {}

    public TaskAdviceDto(String start, String done) {
        this.start = start;
        this.done = done;
    }
}
