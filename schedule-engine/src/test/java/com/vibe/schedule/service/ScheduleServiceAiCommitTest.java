package com.chao.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.client.GoalClient;
import com.chao.common.dto.DailyPlanCommitRequest;
import com.chao.common.dto.DailyPlanCommitResponse;
import com.chao.common.dto.FreeSlotDto;
import com.chao.common.dto.GoalTaskDto;
import com.chao.common.dto.Result;
import com.chao.common.dto.TaskScheduleDto;
import com.chao.schedule.entity.ClassSchedule;
import com.chao.schedule.entity.TaskSchedule;
import com.chao.schedule.mapper.ClassScheduleMapper;
import com.chao.schedule.mapper.PlanCandidateMapper;
import com.chao.schedule.mapper.TaskScheduleMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class ScheduleServiceAiCommitTest {

    @Test
    void springAiCommit_shouldAllocateWithinFreeTime() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        LocalDate date = LocalDate.of(2026, 4, 13);

        List<ClassSchedule> classes = new ArrayList<>();
        classes.add(buildClass(1L, 1, LocalTime.of(8, 0), LocalTime.of(9, 40), "高等数学"));

        List<TaskSchedule> insertedSchedules = new ArrayList<>();
        AtomicLong idSeq = new AtomicLong(260);

        ClassScheduleMapper classScheduleMapper = (ClassScheduleMapper) Proxy.newProxyInstance(
                ClassScheduleMapper.class.getClassLoader(),
                new Class[]{ClassScheduleMapper.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("selectList".equals(name)) {
                        return classes;
                    }
                    if ("selectCount".equals(name)) {
                        return (long) classes.size();
                    }
                    if ("delete".equals(name)) {
                        classes.clear();
                        return 1;
                    }
                    if ("insert".equals(name)) {
                        if (args != null && args.length > 0 && args[0] instanceof ClassSchedule c) {
                            classes.add(c);
                            return 1;
                        }
                        return 1;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                }
        );

        TaskScheduleMapper taskScheduleMapper = (TaskScheduleMapper) Proxy.newProxyInstance(
                TaskScheduleMapper.class.getClassLoader(),
                new Class[]{TaskScheduleMapper.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("selectList".equals(name)) {
                        return insertedSchedules;
                    }
                    if ("delete".equals(name)) {
                        insertedSchedules.clear();
                        return 1;
                    }
                    if ("insert".equals(name)) {
                        if (args != null && args.length > 0 && args[0] instanceof TaskSchedule ts) {
                            if (ts.getId() == null) {
                                ts.setId(idSeq.incrementAndGet());
                            }
                            insertedSchedules.add(ts);
                            return 1;
                        }
                        return 1;
                    }
                    if ("updateById".equals(name)) {
                        return 1;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                }
        );

        PlanCandidateMapper planCandidateMapper = (PlanCandidateMapper) Proxy.newProxyInstance(
                PlanCandidateMapper.class.getClassLoader(),
                new Class[]{PlanCandidateMapper.class},
                (proxy, method, args) -> {
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                }
        );

        List<GoalTaskDto> tasks = List.of(
                buildTask(101L, "理解极限与连续", 60, 3),
                buildTask(102L, "完成练习题", 45, 2),
                buildTask(103L, "研究连续性的概念", 30, 1)
        );

        GoalClient goalClient = (GoalClient) Proxy.newProxyInstance(
                GoalClient.class.getClassLoader(),
                new Class[]{GoalClient.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getPendingTasks".equals(name)) {
                        return Result.success(tasks);
                    }
                    if ("getTasksByIds".equals(name)) {
                        return Result.success(tasks);
                    }
                    if ("updateTaskStatus".equals(name)) {
                        return Result.success("OK");
                    }
                    return null;
                }
        );

        OpenAiCompatClient openAiCompatClient = new OpenAiCompatClient(null, objectMapper, null) {
            @Override
            public String complete(String prompt) {
                return """
                        {
                          "note": "基于课表空闲时间生成当日计划",
                          "candidateSchedules": [
                            {"taskId": 101, "startTime": "2026-04-13T10:00:00", "endTime": "2026-04-13T10:45:00"},
                            {"taskId": 102, "startTime": "2026-04-13T10:55:00", "endTime": "2026-04-13T11:40:00"},
                            {"taskId": 103, "startTime": "2026-04-13T11:50:00", "endTime": "2026-04-13T12:20:00"}
                          ]
                        }
                        """;
            }
        };

        PlanCandidateWorker planCandidateWorker = new PlanCandidateWorker(null, objectMapper, null, null);

        ScheduleService scheduleService = new ScheduleService(
                classScheduleMapper,
                taskScheduleMapper,
                planCandidateMapper,
                goalClient,
                openAiCompatClient,
                objectMapper,
                planCandidateWorker
        );

        DailyPlanCommitRequest req = new DailyPlanCommitRequest();
        req.setDate(date);
        req.setMode("append");

        DailyPlanCommitResponse resp = scheduleService.commitDailyPlan(1L, req);
        Assertions.assertEquals(date, resp.getDate());
        Assertions.assertNotNull(resp.getSchedules());
        Assertions.assertEquals(3, resp.getSchedules().size());

        List<FreeSlotDto> freeSlots = resp.getFreeSlots();
        Assertions.assertNotNull(freeSlots);
        Assertions.assertFalse(freeSlots.isEmpty());

        for (TaskScheduleDto s : resp.getSchedules()) {
            Assertions.assertTrue(withinAnySlot(s.getStartTime(), s.getEndTime(), freeSlots));
        }
    }

    private static boolean withinAnySlot(LocalDateTime start, LocalDateTime end, List<FreeSlotDto> slots) {
        for (FreeSlotDto f : slots) {
            if (f == null || f.getStart() == null || f.getEnd() == null) continue;
            boolean ok = !start.isBefore(f.getStart()) && !end.isAfter(f.getEnd());
            if (ok) return true;
        }
        return false;
    }

    private static ClassSchedule buildClass(Long userId, int dow, LocalTime start, LocalTime end, String name) {
        ClassSchedule c = new ClassSchedule();
        c.setUserId(userId);
        c.setDayOfWeek(dow);
        c.setStartTime(start);
        c.setEndTime(end);
        c.setCourseName(name);
        return c;
    }

    private static GoalTaskDto buildTask(Long id, String title, Integer minutes, Integer priority) {
        GoalTaskDto t = new GoalTaskDto();
        t.setId(id);
        t.setTitle(title);
        t.setEstimatedMinutes(minutes);
        t.setPriority(priority);
        return t;
    }
}
