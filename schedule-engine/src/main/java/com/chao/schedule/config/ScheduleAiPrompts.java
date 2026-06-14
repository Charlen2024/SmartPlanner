package com.chao.schedule.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "smartplanner.schedule.prompts")
public class ScheduleAiPrompts {
    private String planSystem = "";
    private String dailyPlanSystem = "";
    private String candidatePlanSystem = "";
}
