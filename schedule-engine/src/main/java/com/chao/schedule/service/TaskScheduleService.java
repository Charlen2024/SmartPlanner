package com.chao.schedule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chao.common.client.GoalClient;
import com.chao.common.dto.GoalTaskDto;
import com.chao.common.dto.Result;
import com.chao.common.dto.TaskScheduleDto;
import com.chao.schedule.entity.TaskSchedule;
import com.chao.schedule.mapper.TaskScheduleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskScheduleService {

    private final TaskScheduleMapper taskScheduleMapper;
    private final GoalClient goalClient;

    public List<TaskScheduleDto> listTaskSchedules(Long userId, LocalDateTime from, LocalDateTime to) {
        LambdaQueryWrapper<TaskSchedule> qw = new LambdaQueryWrapper<TaskSchedule>()
                .eq(TaskSchedule::getUserId, userId)
                .orderByAsc(TaskSchedule::getStartTime);
        if (from != null) {
            qw.ge(TaskSchedule::getStartTime, from);
        }
        if (to != null) {
            qw.le(TaskSchedule::getStartTime, to);
        }
        List<TaskSchedule> list = taskScheduleMapper.selectList(qw);
        if (list == null || list.isEmpty()) {
            return List.of();
        }

        List<Long> taskIds = list.stream().map(TaskSchedule::getTaskId).distinct().collect(Collectors.toList());
        Map<Long, String> titleMap = new HashMap<>();
        try {
            Result<List<GoalTaskDto>> r = goalClient.getTasksByIds(taskIds);
            if (r != null && r.getCode() == 200 && r.getData() != null) {
                for (GoalTaskDto t : r.getData()) {
                    if (t != null && t.getId() != null) {
                        titleMap.put(t.getId(), t.getTitle());
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return list.stream()
            .map(s -> {
            TaskScheduleDto dto = new TaskScheduleDto();
            dto.setId(s.getId());
            dto.setUserId(s.getUserId());
            dto.setTaskId(s.getTaskId());
            dto.setTaskTitle(titleMap.get(s.getTaskId()));
            dto.setStartTime(s.getStartTime());
            dto.setEndTime(s.getEndTime());
            dto.setStatus(s.getStatus());
            return dto;
        }).collect(Collectors.toList());
    }

    public void updateTaskScheduleStatus(Long scheduleId, Integer status) {
        TaskSchedule ts = new TaskSchedule();
        ts.setId(scheduleId);
        ts.setStatus(status);
        taskScheduleMapper.updateById(ts);
    }

    public void deleteFutureTaskSchedules(Long userId) {
        taskScheduleMapper.delete(new LambdaQueryWrapper<TaskSchedule>()
                .eq(TaskSchedule::getUserId, userId)
                .ge(TaskSchedule::getStartTime, LocalDateTime.now()));
    }

    public void deleteTaskSchedulesByTaskIds(Long userId, List<Long> taskIds) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("未授权");
        }
        List<Long> ids = taskIds == null ? List.of() : taskIds.stream()
                .filter(x -> x != null && x > 0)
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) return;
        taskScheduleMapper.delete(new LambdaQueryWrapper<TaskSchedule>()
                .eq(TaskSchedule::getUserId, userId)
                .in(TaskSchedule::getTaskId, ids));
    }

    public void deleteTaskSchedulesByDate(Long userId, String dateStr) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("未授权");
        if (dateStr == null || dateStr.isBlank()) throw new IllegalArgumentException("日期不能为空");
        LocalDate date = LocalDate.parse(dateStr.trim());
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        taskScheduleMapper.delete(new LambdaQueryWrapper<TaskSchedule>()
                .eq(TaskSchedule::getUserId, userId)
                .ge(TaskSchedule::getStartTime, start)
                .lt(TaskSchedule::getStartTime, end));
    }
}
