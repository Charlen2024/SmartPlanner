package com.chao.common.dto;

import lombok.Data;

import java.time.LocalTime;

@Data
public class ClassScheduleDto {
    private Long id;
    private Long userId;
    private String courseName;
    private Integer dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
    private String location;
    private String semester;
}

