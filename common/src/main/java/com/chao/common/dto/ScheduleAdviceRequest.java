package com.chao.common.dto;

import lombok.Data;
import java.util.List;

@Data
public class ScheduleAdviceRequest {
    private List<ScheduleAdviceItem> items;
    private String moodHint;
}
