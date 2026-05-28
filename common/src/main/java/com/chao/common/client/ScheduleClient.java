package com.chao.common.client;

import com.chao.common.dto.Result;
import com.chao.common.dto.ClassScheduleDto;
import com.chao.common.dto.DailyPlanCommitRequest;
import com.chao.common.dto.DailyPlanCommitResponse;
import com.chao.common.dto.DailyPlanJobStartRequest;
import com.chao.common.dto.DailyPlanJobStartResponse;
import com.chao.common.dto.DailyPlanJobStatusResponse;
import com.chao.common.dto.GeneratePlanCandidateRequest;
import com.chao.common.dto.PlanCandidateDto;
import com.chao.common.dto.ScheduleImportResultDto;
import com.chao.common.dto.TaskScheduleDto;
import lombok.Data;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

@FeignClient(name = "schedule-engine")
public interface ScheduleClient {
    @PostMapping(value = "/api/schedule/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Result<ScheduleImportResultDto> importSchedule(@RequestParam("userId") Long userId, @RequestPart("file") MultipartFile file, @RequestParam(value = "firstWeekMonday", required = false) String firstWeekMonday);

    @GetMapping("/api/schedule/classes")
    Result<List<ClassScheduleDto>> listClasses(@RequestParam("userId") Long userId, @RequestParam(value = "dayOfWeek", required = false) Integer dayOfWeek, @RequestParam(value = "date", required = false) String date, @RequestParam(value = "firstWeekMonday", required = false) String firstWeekMonday);

    @DeleteMapping("/api/schedule/classes")
    Result<String> deleteClasses(@RequestParam("userId") Long userId);

    @GetMapping("/api/schedule/free-time")
    Result<List<TimeSlot>> getFreeTimeSlots(@RequestParam("userId") Long userId, @RequestParam("date") String date, @RequestParam(value = "firstWeekMonday", required = false) String firstWeekMonday);

    @PutMapping("/api/schedule/first-week-monday")
    Result<String> updateFirstWeekMonday(@RequestParam("userId") Long userId, @RequestParam(value = "firstWeekMonday", required = false) String firstWeekMonday);

    @PostMapping("/api/schedule/auto-schedule")
    Result<String> autoSchedule(@RequestParam("userId") Long userId);

    @PostMapping("/api/schedule/plan-candidates")
    Result<PlanCandidateDto> generatePlanCandidate(@RequestParam("userId") Long userId, @RequestBody GeneratePlanCandidateRequest request);

    @PostMapping("/api/schedule/plan-candidates/{candidateId}/decision")
    Result<String> decidePlanCandidate(
            @PathVariable("candidateId") Long candidateId,
            @RequestParam("userId") Long userId,
            @RequestParam("accept") Boolean accept,
            @RequestParam(value = "useSuggestedSlots", required = false) Boolean useSuggestedSlots);

    @GetMapping("/api/schedule/plan-candidates")
    Result<List<PlanCandidateDto>> listPlanCandidates(@RequestParam("userId") Long userId, @RequestParam(value = "date", required = false) String date);

    @PostMapping("/api/schedule/daily-plan/commit")
    Result<DailyPlanCommitResponse> commitDailyPlan(@RequestParam("userId") Long userId, @RequestBody DailyPlanCommitRequest request);

    @PostMapping("/api/schedule/daily-plan/jobs")
    Result<DailyPlanJobStartResponse> startDailyPlanJob(@RequestParam("userId") Long userId, @RequestBody DailyPlanJobStartRequest request);

    @GetMapping("/api/schedule/daily-plan/jobs/{jobId}")
    Result<DailyPlanJobStatusResponse> getDailyPlanJobStatus(@RequestParam("userId") Long userId, @PathVariable("jobId") String jobId);

    @GetMapping("/api/schedule/task-schedules")
    Result<List<TaskScheduleDto>> listTaskSchedules(
            @RequestParam("userId") Long userId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to);

    @PatchMapping("/api/schedule/task-schedules/{scheduleId}/status")
    Result<String> updateTaskScheduleStatus(@PathVariable("scheduleId") Long scheduleId, @RequestParam("status") Integer status);

    @PostMapping("/api/schedule/task-schedules/delete-by-task-ids")
    Result<String> deleteTaskSchedulesByTaskIds(@RequestParam("userId") Long userId, @RequestBody List<Long> taskIds);

    @DeleteMapping("/api/schedule/task-schedules/by-date")
    Result<String> deleteTaskSchedulesByDate(@RequestParam("userId") Long userId, @RequestParam("date") String date);

    @DeleteMapping("/api/schedule/task-schedules/{scheduleId}")
    Result<String> deleteTaskSchedule(@RequestParam("userId") Long userId, @PathVariable("scheduleId") Long scheduleId);

    @DeleteMapping("/api/schedule/task-schedules/future")
    Result<String> deleteFutureTaskSchedules(@RequestParam("userId") Long userId);

    @Data
    class TimeSlot {
        private LocalDateTime start;
        private LocalDateTime end;
    }
}
