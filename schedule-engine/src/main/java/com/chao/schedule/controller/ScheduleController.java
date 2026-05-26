package com.chao.schedule.controller;

import com.chao.common.util.DateUtils;
import com.chao.common.dto.Result;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.DailyPlanCommitRequest;
import com.chao.common.dto.DailyPlanCommitResponse;
import com.chao.common.dto.DailyPlanJobStartRequest;
import com.chao.common.dto.DailyPlanJobStartResponse;
import com.chao.common.dto.DailyPlanJobStatusResponse;
import com.chao.common.dto.GeneratePlanCandidateRequest;
import com.chao.common.dto.PlanCandidateDto;
import com.chao.common.dto.ScheduleImportResultDto;
import com.chao.schedule.entity.ClassSchedule;
import com.chao.common.dto.TaskScheduleDto;
import com.chao.schedule.service.ScheduleService;
import com.chao.schedule.service.DailyPlanJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/schedule")
@RequiredArgsConstructor
public class ScheduleController {
    private final ScheduleService scheduleService;
    private final DailyPlanJobService dailyPlanJobService;

    /**
     * 上传课表 (iCalendar/Excel)
     */
    @PostMapping("/import")
    public Result<ScheduleImportResultDto> importSchedule(@RequestParam Long userId, @RequestParam MultipartFile file) {
        return Result.success(scheduleService.parseAndSaveSchedule(userId, file));
    }

    @GetMapping("/classes")
    public Result<List<ClassSchedule>> listClasses(@RequestParam Long userId, @RequestParam(required = false) Integer dayOfWeek) {
        return Result.success(scheduleService.listClassSchedules(userId, dayOfWeek));
    }

    @DeleteMapping("/classes")
    public Result<String> deleteClasses(@RequestParam Long userId) {
        scheduleService.deleteClassSchedules(userId);
        return Result.success("删除成功");
    }

    /**
     * 获取指定日期的空闲时段
     */
    @GetMapping("/free-time")
    public Result<List<ScheduleClient.TimeSlot>> getFreeTimeSlots(@RequestParam Long userId, @RequestParam String date) {
        return Result.success(scheduleService.calculateFreeTime(userId, date));
    }

    /**
     * 智能排程接口：将任务智能分配到空闲时段
     */
    @PostMapping("/auto-schedule")
    public Result<String> autoSchedule(@RequestParam Long userId) {
        scheduleService.smartScheduleAsync(userId);
        return Result.success("已开始智能排程，请稍后刷新查看结果");
    }

    @GetMapping("/task-schedules")
    public Result<List<TaskScheduleDto>> listTaskSchedules(
            @RequestParam Long userId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return Result.success(scheduleService.listTaskSchedules(userId, DateUtils.parseLocalDateTime(from), DateUtils.parseLocalDateTime(to)));
    }

    @PatchMapping("/task-schedules/{scheduleId}/status")
    public Result<String> updateTaskScheduleStatus(@PathVariable Long scheduleId, @RequestParam Integer status) {
        scheduleService.updateTaskScheduleStatus(scheduleId, status);
        return Result.success("更新成功");
    }

    @DeleteMapping("/task-schedules/future")
    public Result<String> deleteFutureTaskSchedules(@RequestParam Long userId) {
        scheduleService.deleteFutureTaskSchedules(userId);
        return Result.success("删除成功");
    }

    @PostMapping("/task-schedules/delete-by-task-ids")
    public Result<String> deleteTaskSchedulesByTaskIds(@RequestParam Long userId, @RequestBody List<Long> taskIds) {
        scheduleService.deleteTaskSchedulesByTaskIds(userId, taskIds);
        return Result.success("删除成功");
    }

    @DeleteMapping("/task-schedules/by-date")
    public Result<String> deleteTaskSchedulesByDate(@RequestParam Long userId, @RequestParam String date) {
        scheduleService.deleteTaskSchedulesByDate(userId, date);
        return Result.success("已删除该日排程");
    }

    @PostMapping("/plan-candidates")
    public Result<PlanCandidateDto> generatePlanCandidate(@RequestParam Long userId, @RequestBody GeneratePlanCandidateRequest request) {
        return Result.success(scheduleService.generatePlanCandidate(userId, request));
    }

    @PostMapping("/plan-candidates/{candidateId}/decision")
    public Result<String> decidePlanCandidate(
            @PathVariable Long candidateId,
            @RequestParam Long userId,
            @RequestParam Boolean accept,
            @RequestParam(required = false) Boolean useSuggestedSlots) {
        scheduleService.decidePlanCandidate(userId, candidateId, accept, useSuggestedSlots);
        return Result.success("ok");
    }

    @GetMapping("/plan-candidates")
    public Result<List<PlanCandidateDto>> listPlanCandidates(@RequestParam Long userId, @RequestParam(required = false) String date) {
        return Result.success(scheduleService.listPlanCandidates(userId, date));
    }

    @PostMapping("/daily-plan/commit")
    public Result<DailyPlanCommitResponse> commitDailyPlan(@RequestParam Long userId, @RequestBody DailyPlanCommitRequest request) {
        return Result.success(scheduleService.commitDailyPlan(userId, request));
    }

    @PostMapping("/daily-plan/jobs")
    public Result<DailyPlanJobStartResponse> startDailyPlanJob(@RequestParam Long userId, @RequestBody DailyPlanJobStartRequest request) {
        return Result.success(dailyPlanJobService.start(userId, request));
    }

    @GetMapping("/daily-plan/jobs/{jobId}")
    public Result<DailyPlanJobStatusResponse> getDailyPlanJobStatus(@RequestParam Long userId, @PathVariable String jobId) {
        return Result.success(dailyPlanJobService.status(userId, jobId));
    }
}
