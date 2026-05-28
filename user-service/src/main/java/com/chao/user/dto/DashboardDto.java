package com.chao.user.dto;

import com.chao.common.client.ResourceClient;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.ClassScheduleDto;
import com.chao.common.dto.GoalDto;
import com.chao.common.dto.GoalTaskDto;
import com.chao.common.dto.TaskScheduleDto;
import lombok.Data;

import java.util.List;

@Data
public class DashboardDto {
    private List<GoalDto> goals;
    private List<GoalProgressDto> goalProgress;
    private List<GoalTaskDto> pendingTasks;
    private List<ScheduleClient.TimeSlot> freeTimeSlots;
    private List<TaskScheduleDto> taskSchedules;
    private Long streak;
    private List<ResourceClient.CourseResource> resources;
    private List<ClassScheduleDto> classes;
}

