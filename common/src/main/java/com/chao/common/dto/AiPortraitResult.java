package com.chao.common.dto;

import lombok.Data;
import java.util.List;

@Data
public class AiPortraitResult {
    private Integer morningPersonScore;
    private Integer focusDurationAvg;
    private Double procrastinationIndex;
    private SchedulePreferenceDto recommendation;
    private List<String> tips;
}
