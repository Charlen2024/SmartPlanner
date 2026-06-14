package com.chao.user.dto;

import com.chao.common.dto.SchedulePreferenceDto;
import com.chao.common.dto.UserHabitDto;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class UserPortraitDto {
    private UserHabitDto habits;
    private UserInsightDto insights;
    private SchedulePreferenceDto recommendation;
    private List<String> tips;
    private Map<String, Object> computation = new LinkedHashMap<>();
    private Map<String, Object> trends = new LinkedHashMap<>();
    private List<Map<String, Object>> bestTimeSlots = new ArrayList<>();
}

