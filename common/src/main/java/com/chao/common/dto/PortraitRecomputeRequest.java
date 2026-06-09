package com.chao.common.dto;

import lombok.Data;
import java.util.List;

@Data
public class PortraitRecomputeRequest {
    private List<PunchRecordDto> punchRecords;
    private List<TaskScheduleDto> schedules;
    private int streak;
    private Double onTimeRate;
    private Double avgDelayMinutes;
    private Double completionRate;
    private Integer matchedPunchCount;
    private Integer morningPersonScore;
    private Integer focusDurationAvg;
    private Double procrastinationIndex;
}
