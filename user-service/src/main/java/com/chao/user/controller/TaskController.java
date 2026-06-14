package com.chao.user.controller;

import com.chao.common.client.AgentAdviceClient;
import com.chao.common.client.GoalClient;
import com.chao.common.dto.*;
import com.chao.user.dto.TaskResourcesRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class TaskController {

    private final GoalClient goalClient;
    private final AgentAdviceClient agentAdviceClient;
    private final RedissonClient redissonClient;
    private final UserControllerSupport support;

    private static final String TASK_ADVICE_KEY_PREFIX = "sp:task:advice:v1:";

    @GetMapping("/tasks/pending")
    public Result<List<GoalTaskDto>> pendingTasks(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return goalClient.getPendingTasks(support.resolveUserId(jwt, headerUserId, userId));
    }

    @PostMapping("/tasks/by-ids")
    public Result<List<GoalTaskDto>> tasksByIds(@RequestBody List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Result.success(List.of());
        }
        return goalClient.getTasksByIds(taskIds);
    }

    @PostMapping("/tasks/advice")
    public Result<Map<Long, String>> taskAdvice(@RequestBody List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Result.success(Map.of());
        }
        Map<Long, String> cached = new HashMap<>();
        List<Long> uncached = new ArrayList<>();
        if (redissonClient != null) {
            for (Long tid : taskIds) {
                if (tid == null || tid <= 0) continue;
                try {
                    String key = TASK_ADVICE_KEY_PREFIX + tid;
                    String val = (String) redissonClient.getBucket(key).get();
                    if (val != null && !val.isBlank()) {
                        cached.put(tid, val);
                    } else {
                        uncached.add(tid);
                    }
                } catch (Exception ignored) {
                    uncached.add(tid);
                }
            }
        } else {
            uncached.addAll(taskIds);
        }

        if (!uncached.isEmpty()) {
            List<GoalTaskDto> tasks = goalClient.getTasksByIds(uncached).getData();
            if (tasks != null && !tasks.isEmpty()) {
                Map<Long, String> fresh = agentAdviceClient.adviseTasks(tasks).getData();
                if (fresh != null) {
                    if (redissonClient != null) {
                        for (Map.Entry<Long, String> e : fresh.entrySet()) {
                            try {
                                String key = TASK_ADVICE_KEY_PREFIX + e.getKey();
                                redissonClient.getBucket(key).set(e.getValue());
                                redissonClient.getBucket(key).expire(Duration.ofDays(7));
                            } catch (Exception ignored) {}
                        }
                    }
                    cached.putAll(fresh);
                }
            }
        }
        return Result.success(cached);
    }

    @PatchMapping("/tasks/{taskId}/status")
    public Result<String> updateTaskStatus(@PathVariable Long taskId, @RequestParam Integer status) {
        return goalClient.updateTaskStatus(taskId, status);
    }

    @PostMapping("/tasks/resources")
    public Result<Map<Long, List<CourseResourceDto>>> taskResources(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody(required = false) TaskResourcesRequest request) {
        Long uid = support.resolveUserId(jwt, headerUserId, userId);
        List<Long> ids = request != null ? request.getTaskIds() : null;
        ids = ids != null ? ids.stream().filter(x -> x != null && x > 0).distinct().collect(Collectors.toList()) : List.of();
        if (ids.isEmpty()) {
            return Result.success(Map.of());
        }
        int topK = request != null && request.getTopK() != null ? request.getTopK() : 3;
        topK = Math.max(1, Math.min(topK, 6));
        boolean refresh = request != null && Boolean.TRUE.equals(request.getRefresh());

        Result<List<GoalTaskDto>> taskRes = goalClient.getTasksByIds(ids);
        List<GoalTaskDto> tasks = taskRes != null ? taskRes.getData() : List.of();
        Map<Long, GoalTaskDto> taskById = new HashMap<>();
        if (tasks != null) {
            for (GoalTaskDto t : tasks) {
                if (t != null && t.getId() != null) {
                    taskById.put(t.getId(), t);
                }
            }
        }

        Map<Long, List<CourseResourceDto>> out = new LinkedHashMap<>();
        for (Long taskId : ids) {
            GoalTaskDto t = taskById.get(taskId);
            List<CourseResourceDto> list = support.getOrBuildTaskResources(uid, taskId, t, topK, refresh);
            out.put(taskId, list != null ? list : List.of());
        }
        return Result.success(out);
    }
}
