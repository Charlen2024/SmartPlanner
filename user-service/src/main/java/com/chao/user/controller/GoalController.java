package com.chao.user.controller;

import com.chao.common.client.GoalClient;
import com.chao.common.dto.*;
import com.chao.user.service.AppUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class GoalController {

    private final GoalClient goalClient;
    private final AppUserService appUserService;
    private final UserControllerSupport support;

    @PostMapping("/goals/ai")
    public Result<GoalDto> createGoalByAi(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody String goalDescription) {
        Long uid = support.resolveUserId(jwt, headerUserId, userId);
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
        return goalClient.createGoalRecord(support.resolveUserId(jwt, headerUserId, userId), title, description, deadline);
    }

    @GetMapping("/goals")
    public Result<List<GoalDto>> listGoals(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return goalClient.listGoals(support.resolveUserId(jwt, headerUserId, userId));
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

    @GetMapping("/goals/{goalId}/unfinished-count")
    public Result<java.util.Map<String, Long>> countUnfinishedTasks(@PathVariable Long goalId) {
        return goalClient.countUnfinishedTasks(goalId);
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
        return goalClient.createTask(goalId, support.resolveUserId(jwt, headerUserId, userId),
                parentId, title, description, priority, estimatedMinutes, deadline);
    }

    @GetMapping("/goals/{goalId}/tasks")
    public Result<List<GoalTaskDto>> listTasks(
            @PathVariable Long goalId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return goalClient.listTasks(goalId, support.resolveUserId(jwt, headerUserId, userId));
    }

    @PostMapping("/goals/{goalId}/tasks/regenerate")
    public Result<String> regenerateTasks(
            @PathVariable Long goalId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody(required = false) String feedback) {
        return goalClient.regenerateTasks(goalId, support.resolveUserId(jwt, headerUserId, userId), feedback);
    }

    @GetMapping("/journals")
    public Result<List<UserJournalDto>> journals(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long goalId) {
        return goalClient.listJournals(support.resolveUserId(jwt, headerUserId, userId), goalId);
    }

    @PostMapping("/journals")
    public Result<String> createJournal(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long goalId,
            @RequestParam String content,
            @RequestParam(required = false) String mood) {
        return goalClient.createJournal(support.resolveUserId(jwt, headerUserId, userId), goalId, content, mood);
    }

    @DeleteMapping("/journals/{journalId}")
    public Result<String> deleteJournal(
            @PathVariable Long journalId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return goalClient.deleteJournal(support.resolveUserId(jwt, headerUserId, userId), journalId);
    }
}
