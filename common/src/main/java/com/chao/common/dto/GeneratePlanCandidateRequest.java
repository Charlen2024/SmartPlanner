package com.chao.common.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class GeneratePlanCandidateRequest {
    private LocalDate date;
    private List<FreeSlotDto> freeSlots;
    private SchedulePreferenceDto preference;
}

