package com.chao.user.controller;

import com.chao.common.client.GoalClient;
import com.chao.common.client.PunchClient;
import com.chao.common.client.ResourceClient;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.*;
import com.chao.user.dto.DashboardDto;
import com.chao.user.service.UserService;
import com.chao.user.service.TaskAdviceAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final GoalClient goalClient;
    private final ScheduleClient scheduleClient;
    private final PunchClient punchClient;
    private final ResourceClient resourceClient;
    private final com.chao.user.service.AppUserService appUserService;
    private final TaskAdviceAiService taskAdviceAiService;

    @GetMapping("/dashboard")
    public Result<DashboardDto> dashboard(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String topic) {
        return Result.success(userService.getDashboard(resolveUserId(jwt, headerUserId, userId), date, topic));
    }

    @PostMapping("/goals/ai")
    public Result<GoalDto> createGoalByAi(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody String goalDescription) {
        Long uid = resolveUserId(jwt, headerUserId, userId);
        com.chao.user.entity.AppUser u = appUserService.getById(uid);
        if (u != null && Boolean.FALSE.equals(u.getScheduleImported())) {
            return Result.fail(400, "请先导入课表后再生成学习计划");
        }
        return goalClient.createGoalByAi(uid, goalDescription);
    }

    @PostMapping("/goals")
    public Result<GoalDto> createGoal(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String deadline) {
        return goalClient.createGoalRecord(resolveUserId(jwt, headerUserId, userId), title, description, deadline);
    }

    @GetMapping("/goals")
    public Result<List<GoalDto>> listGoals(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return goalClient.listGoals(resolveUserId(jwt, headerUserId, userId));
    }

    @GetMapping("/tasks/pending")
    public Result<List<GoalTaskDto>> pendingTasks(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return goalClient.getPendingTasks(resolveUserId(jwt, headerUserId, userId));
    }

    @PostMapping("/tasks/by-ids")
    public Result<List<GoalTaskDto>> tasksByIds(@RequestBody List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Result.success(List.of());
        }
        return goalClient.getTasksByIds(taskIds);
    }

    @PostMapping("/tasks/advice")
    public Result<java.util.Map<Long, String>> taskAdvice(@RequestBody List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Result.success(java.util.Map.of());
        }
        List<GoalTaskDto> tasks = goalClient.getTasksByIds(taskIds).getData();
        return Result.success(taskAdviceAiService.advise(tasks));
    }

    @GetMapping("/journals")
    public Result<List<UserJournalDto>> journals(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long goalId) {
        return goalClient.listJournals(resolveUserId(jwt, headerUserId, userId), goalId);
    }

    @PostMapping("/journals")
    public Result<String> createJournal(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long goalId,
            @RequestParam String content,
            @RequestParam(required = false) String mood) {
        return goalClient.createJournal(resolveUserId(jwt, headerUserId, userId), goalId, content, mood);
    }

    @GetMapping("/schedule/free-time")
    public Result<List<ScheduleClient.TimeSlot>> freeTime(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam String date) {
        return scheduleClient.getFreeTimeSlots(resolveUserId(jwt, headerUserId, userId), date);
    }

    @PostMapping("/schedule/auto")
    public Result<String> autoSchedule(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return scheduleClient.autoSchedule(resolveUserId(jwt, headerUserId, userId));
    }

    @PostMapping("/schedule/plan-candidates")
    public Result<PlanCandidateDto> generatePlanCandidate(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody GeneratePlanCandidateRequest request) {
        return scheduleClient.generatePlanCandidate(resolveUserId(jwt, headerUserId, userId), request);
    }

    @PostMapping("/schedule/plan-candidates/{candidateId}/decision")
    public Result<String> decidePlanCandidate(
            @PathVariable Long candidateId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam Boolean accept,
            @RequestParam(required = false) Boolean useSuggestedSlots) {
        return scheduleClient.decidePlanCandidate(candidateId, resolveUserId(jwt, headerUserId, userId), accept, useSuggestedSlots);
    }

    @GetMapping("/schedule/plan-candidates")
    public Result<List<PlanCandidateDto>> listPlanCandidates(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String date) {
        return scheduleClient.listPlanCandidates(resolveUserId(jwt, headerUserId, userId), date);
    }

    @PostMapping("/schedule/daily-plan/commit")
    public Result<DailyPlanCommitResponse> commitDailyPlan(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody DailyPlanCommitRequest request) {
        return scheduleClient.commitDailyPlan(resolveUserId(jwt, headerUserId, userId), request);
    }

    @PostMapping("/schedule/daily-plan/jobs")
    public Result<DailyPlanJobStartResponse> startDailyPlanJob(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody DailyPlanJobStartRequest request) {
        return scheduleClient.startDailyPlanJob(resolveUserId(jwt, headerUserId, userId), request);
    }

    @GetMapping("/schedule/daily-plan/jobs/{jobId}")
    public Result<DailyPlanJobStatusResponse> getDailyPlanJobStatus(
            @PathVariable String jobId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return scheduleClient.getDailyPlanJobStatus(resolveUserId(jwt, headerUserId, userId), jobId);
    }

    @PostMapping("/schedule/import")
    public Result<ScheduleImportResultDto> importSchedule(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam MultipartFile file) {
        Long uid = resolveUserId(jwt, headerUserId, userId);
        Result<ScheduleImportResultDto> r = scheduleClient.importSchedule(uid, file);
        if (r != null && r.getCode() == 200) {
            appUserService.markScheduleImported(uid);
        }
        return r;
    }

    @PostMapping("/punch/submit")
    public Result<String> submitPunch(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam Long taskId,
            @RequestParam Integer type,
            @RequestParam(required = false) Integer durationSeconds,
            @RequestParam(required = false) Long startedAtMs,
            @RequestParam(required = false) Long endedAtMs,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) MultipartFile evidence) {
        return punchClient.submitPunch(resolveUserId(jwt, headerUserId, userId), taskId, type, durationSeconds, startedAtMs, endedAtMs, location, evidence);
    }

    @GetMapping("/resources/search")
    public Result<List<ResourceClient.CourseResource>> searchResources(@RequestParam String topic) {
        return resourceClient.searchOnlineCourses(topic);
    }

    @GetMapping("/resources/search/advice")
    public Result<ResourceClient.ResourceAdviceResponse> searchResourcesWithAdvice(@RequestParam String topic) {
        return resourceClient.searchOnlineCoursesWithAdvice(topic);
    }

    @PostMapping("/resources/search/advice/jobs")
    public Result<ResourceAdviceJobStartResponse> startResourceAdviceJob(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody ResourceAdviceJobStartRequest request) {
        return resourceClient.startResourceAdviceJob(resolveUserId(jwt, headerUserId, userId), request);
    }

    @GetMapping("/resources/search/advice/jobs/{jobId}")
    public Result<ResourceAdviceJobStatusResponse> getResourceAdviceJobStatus(
            @PathVariable String jobId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return resourceClient.getResourceAdviceJobStatus(resolveUserId(jwt, headerUserId, userId), jobId);
    }

    @PostMapping("/resources")
    public Result<CourseResourceDto> createResource(
            @RequestParam String topic,
            @RequestParam String title,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false, name = "url") String url,
            @RequestParam(required = false, name = "summary") String summary) {
        return resourceClient.createResource(topic, title, platform, url, summary);
    }

    @GetMapping("/resources")
    public Result<List<CourseResourceDto>> listResources(@RequestParam(required = false) String topic) {
        return resourceClient.listResources(topic);
    }

    @GetMapping("/resources/{id}")
    public Result<CourseResourceDto> getResource(@PathVariable Long id) {
        return resourceClient.getResource(id);
    }

    @DeleteMapping("/resources/{id}")
    public Result<String> deleteResource(@PathVariable Long id) {
        return resourceClient.deleteResource(id);
    }

    @GetMapping("/schedule/task-schedules")
    public Result<List<TaskScheduleDto>> listTaskSchedules(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Long uid = resolveUserId(jwt, headerUserId, userId);
        return scheduleClient.listTaskSchedules(uid, from, to);
    }

    @PatchMapping("/schedule/task-schedules/{scheduleId}/status")
    public Result<String> updateTaskScheduleStatus(@PathVariable Long scheduleId, @RequestParam Integer status) {
        return scheduleClient.updateTaskScheduleStatus(scheduleId, status);
    }

    @DeleteMapping("/schedule/task-schedules/future")
    public Result<String> deleteFutureTaskSchedules(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return scheduleClient.deleteFutureTaskSchedules(resolveUserId(jwt, headerUserId, userId));
    }

    @GetMapping("/schedule/classes")
    public Result<List<ClassScheduleDto>> listClasses(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer dayOfWeek) {
        return scheduleClient.listClasses(resolveUserId(jwt, headerUserId, userId), dayOfWeek);
    }

    @DeleteMapping("/schedule/classes")
    public Result<String> deleteClasses(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return scheduleClient.deleteClasses(resolveUserId(jwt, headerUserId, userId));
    }

    @GetMapping("/punch/records")
    public Result<List<PunchRecordDto>> listPunchRecords(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return punchClient.listRecords(resolveUserId(jwt, headerUserId, userId), null, null, null);
    }

    @DeleteMapping("/punch/records/{recordId}")
    public Result<String> deletePunchRecord(@PathVariable Long recordId) {
        return punchClient.deleteRecord(recordId);
    }

    @GetMapping("/punch/streak")
    public Result<Long> getStreak(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return punchClient.getStreak(resolveUserId(jwt, headerUserId, userId));
    }

    @GetMapping("/punch/habits")
    public Result<UserHabitDto> getHabits(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return punchClient.getHabits(resolveUserId(jwt, headerUserId, userId));
    }

    @PutMapping("/punch/habits")
    public Result<UserHabitDto> updateHabits(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer morningPersonScore,
            @RequestParam(required = false) Integer focusDurationAvg,
            @RequestParam(required = false) Float procrastinationIndex) {
        return punchClient.updateHabits(resolveUserId(jwt, headerUserId, userId), morningPersonScore, focusDurationAvg, procrastinationIndex);
    }

    @GetMapping("/goals/{goalId}")
    public Result<GoalDto> getGoal(@PathVariable Long goalId) {
        return goalClient.getGoal(goalId);
    }

    @PutMapping("/goals/{goalId}")
    public Result<String> updateGoal(
            @PathVariable Long goalId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String deadline) {
        return goalClient.updateGoal(goalId, title, description, status, deadline);
    }

    @DeleteMapping("/goals/{goalId}")
    public Result<String> deleteGoal(@PathVariable Long goalId) {
        return goalClient.deleteGoal(goalId);
    }

    @PostMapping("/goals/{goalId}/tasks")
    public Result<GoalTaskDto> createTask(
            @PathVariable Long goalId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long parentId,
            @RequestParam String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Integer priority,
            @RequestParam(required = false) Integer estimatedMinutes,
            @RequestParam(required = false) String deadline) {
        return goalClient.createTask(goalId, resolveUserId(jwt, headerUserId, userId), parentId, title, description, priority, estimatedMinutes, deadline);
    }

    @GetMapping("/goals/{goalId}/tasks")
    public Result<List<GoalTaskDto>> listTasks(
            @PathVariable Long goalId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return goalClient.listTasks(goalId, resolveUserId(jwt, headerUserId, userId));
    }

    @PostMapping("/goals/{goalId}/tasks/regenerate")
    public Result<String> regenerateTasks(
            @PathVariable Long goalId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody(required = false) String feedback) {
        Long uid = resolveUserId(jwt, headerUserId, userId);
        return goalClient.regenerateTasks(goalId, uid, feedback);
    }

    @PatchMapping("/tasks/{taskId}/status")
    public Result<String> updateTaskStatus(@PathVariable Long taskId, @RequestParam Integer status) {
        return goalClient.updateTaskStatus(taskId, status);
    }

    private Long resolveUserId(Jwt jwt, Long headerUserId, Long userId) {
        Long jwtUserId = null;
        if (jwt != null) {
            Object claim = jwt.getClaims().get("userId");
            if (claim != null) {
                jwtUserId = Long.valueOf(String.valueOf(claim));
            }
        }

        if (jwtUserId != null) {
            if (headerUserId != null && !jwtUserId.equals(headerUserId)) {
                throw new IllegalArgumentException("userId mismatch");
            }
            if (userId != null && !jwtUserId.equals(userId)) {
                throw new IllegalArgumentException("userId mismatch");
            }
            return jwtUserId;
        }

        if (headerUserId != null && userId != null && !headerUserId.equals(userId)) {
            throw new IllegalArgumentException("userId mismatch");
        }
        if (headerUserId != null) {
            return headerUserId;
        }
        if (userId != null) {
            return userId;
        }
        throw new IllegalArgumentException("userId required");
    }
}
