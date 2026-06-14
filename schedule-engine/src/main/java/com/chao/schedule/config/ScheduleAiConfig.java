package com.chao.schedule.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "smartplanner.schedule")
public class ScheduleAiConfig {
    private int sessionMinutes = 45;
    private int breakMinutes = 10;
    private int maxDailyMinutes = 240;
    private int sessionMinutesShort = 25;
    private int maxDailyMinutesConservative = 180;
    private int minSlotMinutes = 10;
    private int candidateAiTimeoutSeconds = 600;
    private int scheduleAiTimeoutSeconds = 170;
    private float defaultProcrastinationIndex = 0.3f;
    private float procrastinationThreshold = 0.6f;
    private String studyStart = "08:00";
    private String studyEnd = "22:00";
}
