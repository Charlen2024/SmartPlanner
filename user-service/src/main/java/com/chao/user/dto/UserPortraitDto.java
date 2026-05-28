package com.chao.user.dto;

import com.chao.common.dto.SchedulePreferenceDto;
import com.chao.common.dto.UserHabitDto;
import lombok.Data;

import java.util.List;

@Data
public class UserPortraitDto {
    private UserHabitDto habits;
    private UserInsightDto insights;
    private SchedulePreferenceDto recommendation;
    private List<String> tips;
}

