package com.chao.common.dto;

import lombok.Data;
import java.util.Map;

@Data
public class ScheduleAdviceResponse {
    private String header;
    private Map<Long, TaskAdviceDto> items;

    public ScheduleAdviceResponse() {}

    public ScheduleAdviceResponse(String header, Map<Long, TaskAdviceDto> items) {
        this.header = header;
        this.items = items;
    }
}
