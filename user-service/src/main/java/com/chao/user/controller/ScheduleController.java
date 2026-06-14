package com.chao.user.controller;

import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.*;
import com.chao.user.service.AppUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleClient scheduleClient;
    private final AppUserService appUserService;
    private final RestTemplate restTemplate;
    private final UserControllerSupport support;

    @GetMapping("/schedule/free-time")
    public Result<List<ScheduleClient.TimeSlot>> freeTime(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam String date) {
        Long uid = support.resolveUserId(jwt, headerUserId, userId);
        String fwm = null;
        com.chao.user.entity.AppUser u = appUserService.getById(uid);
        if (u != null && u.getFirstWeekMonday() != null) {
            fwm = u.getFirstWeekMonday().toString();
        }
        return scheduleClient.getFreeTimeSlots(uid, date, fwm);
    }

    @PostMapping("/schedule/auto")
    public Result<String> autoSchedule(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return scheduleClient.autoSchedule(support.resolveUserId(jwt, headerUserId, userId));
    }

    @PostMapping("/schedule/plan-candidates")
    public Result<PlanCandidateDto> generatePlanCandidate(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody GeneratePlanCandidateRequest request) {
        Long uid = support.resolveUserId(jwt, headerUserId, userId);
        if (request != null && request.getPreference() == null) {
            request.setPreference(support.buildSchedulePreference(uid));
        }
        return scheduleClient.generatePlanCandidate(uid, request);
    }

    @PostMapping("/schedule/plan-candidates/{candidateId}/decision")
    public Result<String> decidePlanCandidate(
            @PathVariable Long candidateId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam Boolean accept,
            @RequestParam(required = false) Boolean useSuggestedSlots) {
        return scheduleClient.decidePlanCandidate(candidateId, support.resolveUserId(jwt, headerUserId, userId), accept, useSuggestedSlots);
    }

    @GetMapping("/schedule/plan-candidates")
    public Result<List<PlanCandidateDto>> listPlanCandidates(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String date) {
        return scheduleClient.listPlanCandidates(support.resolveUserId(jwt, headerUserId, userId), date);
    }

    @PostMapping("/schedule/daily-plan/commit")
    public Result<DailyPlanCommitResponse> commitDailyPlan(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody DailyPlanCommitRequest request) {
        Long uid = support.resolveUserId(jwt, headerUserId, userId);
        if (request != null && request.getPreference() == null) {
            request.setPreference(support.buildSchedulePreference(uid));
        }
        return scheduleClient.commitDailyPlan(uid, request);
    }

    @PostMapping("/schedule/daily-plan/jobs")
    public Result<DailyPlanJobStartResponse> startDailyPlanJob(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody DailyPlanJobStartRequest request) {
        Long uid = support.resolveUserId(jwt, headerUserId, userId);
        if (request != null && request.getPreference() == null) {
            request.setPreference(support.buildSchedulePreference(uid));
        }
        return scheduleClient.startDailyPlanJob(uid, request);
    }

    @GetMapping("/schedule/daily-plan/jobs/{jobId}")
    public Result<DailyPlanJobStatusResponse> getDailyPlanJobStatus(
            @PathVariable String jobId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return scheduleClient.getDailyPlanJobStatus(support.resolveUserId(jwt, headerUserId, userId), jobId);
    }

    @PostMapping("/schedule/import")
    public Result<ScheduleImportResultDto> importSchedule(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam MultipartFile file,
            @RequestParam(required = false) String firstWeekMonday) {
        Long uid = support.resolveUserId(jwt, headerUserId, userId);

        Result<ScheduleImportResultDto> r;
        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("userId", uid);
            builder.part("file", file.getResource());
            if (firstWeekMonday != null && !firstWeekMonday.isBlank()) {
                builder.part("firstWeekMonday", firstWeekMonday);
            }

            ParameterizedTypeReference<Result<ScheduleImportResultDto>> typeRef =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<Result<ScheduleImportResultDto>> entity = restTemplate.exchange(
                    "http://schedule-engine/api/schedule/import",
                    HttpMethod.POST,
                    new HttpEntity<>(builder.build()),
                    typeRef);
            r = entity.getBody();
        } catch (Exception e) {
            log.error("课表导入请求失败", e);
            return Result.fail(500, "课表导入失败，请稍后重试");
        }
        if (r != null && r.getCode() == 200) {
            appUserService.markScheduleImported(uid);
            if (firstWeekMonday != null) {
                if (firstWeekMonday.isBlank()) {
                    appUserService.clearFirstWeekMonday(uid);
                } else {
                    appUserService.saveFirstWeekMonday(uid, java.time.LocalDate.parse(firstWeekMonday));
                }
            }
        }
        return r;
    }

    @PutMapping("/schedule/first-week-monday")
    public Result<String> updateFirstWeekMonday(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String firstWeekMonday) {
        Long uid = support.resolveUserId(jwt, headerUserId, userId);
        if (firstWeekMonday == null || firstWeekMonday.isBlank()) {
            appUserService.clearFirstWeekMonday(uid);
            scheduleClient.updateFirstWeekMonday(uid, null);
        } else {
            appUserService.saveFirstWeekMonday(uid, java.time.LocalDate.parse(firstWeekMonday));
            scheduleClient.updateFirstWeekMonday(uid, firstWeekMonday);
        }
        return Result.success("ok");
    }

    @GetMapping("/schedule/task-schedules")
    public Result<List<TaskScheduleDto>> listTaskSchedules(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return scheduleClient.listTaskSchedules(support.resolveUserId(jwt, headerUserId, userId), from, to);
    }

    @PatchMapping("/schedule/task-schedules/{scheduleId}/status")
    public Result<String> updateTaskScheduleStatus(@PathVariable Long scheduleId, @RequestParam Integer status) {
        return scheduleClient.updateTaskScheduleStatus(scheduleId, status);
    }

    @DeleteMapping("/schedule/task-schedules/by-date")
    public Result<String> deleteTaskSchedulesByDate(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam String date) {
        return scheduleClient.deleteTaskSchedulesByDate(support.resolveUserId(jwt, headerUserId, userId), date);
    }

    @DeleteMapping("/schedule/task-schedules/future")
    public Result<String> deleteFutureTaskSchedules(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return scheduleClient.deleteFutureTaskSchedules(support.resolveUserId(jwt, headerUserId, userId));
    }

    @PostMapping("/schedule/task-schedules/delete-by-task-ids")
    public Result<String> deleteTaskSchedulesByTaskIds(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody(required = false) List<Long> taskIds) {
        return scheduleClient.deleteTaskSchedulesByTaskIds(support.resolveUserId(jwt, headerUserId, userId), taskIds);
    }

    @GetMapping("/schedule/classes")
    public Result<List<ClassScheduleDto>> listClasses(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer dayOfWeek,
            @RequestParam(required = false) String date) {
        Long uid = support.resolveUserId(jwt, headerUserId, userId);
        String fwm = null;
        com.chao.user.entity.AppUser u = appUserService.getById(uid);
        if (u != null && u.getFirstWeekMonday() != null) {
            fwm = u.getFirstWeekMonday().toString();
        }
        return scheduleClient.listClasses(uid, dayOfWeek, date, fwm);
    }

    @DeleteMapping("/schedule/classes")
    public Result<String> deleteClasses(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return scheduleClient.deleteClasses(support.resolveUserId(jwt, headerUserId, userId));
    }
}
