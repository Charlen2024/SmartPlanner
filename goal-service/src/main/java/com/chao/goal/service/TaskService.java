package com.chao.goal.service;

import com.chao.common.config.RabbitMqConfig;
import com.chao.common.dto.GoalTaskDto;
import com.chao.common.dto.NotificationMessage;
import com.chao.common.util.TextSimilarity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chao.goal.entity.GoalTask;
import com.chao.goal.mapper.GoalTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final GoalTaskMapper goalTaskMapper;
    private final RabbitTemplate rabbitTemplate;

    public GoalTask createTask(Long userId, Long goalId, Long parentId, String title, String description, Integer priority, Integer estimatedMinutes, LocalDateTime deadline) {
        String newTitle = title != null ? title.trim() : "";
        if (newTitle.isBlank()) {
            throw new IllegalArgumentException("任务标题不能为空");
        }

        GoalTask existing = findSimilarExistingTask(userId, goalId, newTitle);
        if (existing != null) {
            throw new DuplicateTaskException(existing.getId(), existing.getTitle());
        }

        GoalTask task = new GoalTask();
        task.setUserId(userId);
        task.setGoalId(goalId);
        task.setParentId(parentId);
        task.setTitle(newTitle);
        task.setDescription(description);
        task.setPriority(priority);
        task.setEstimatedMinutes(estimatedMinutes);
        task.setDeadline(deadline);
        task.setStatus(0);
        goalTaskMapper.insert(task);

        try {
            NotificationMessage notif = new NotificationMessage();
            notif.setUserId(userId);
            notif.setType("AGENT_REMINDER");
            notif.setContent("trigger=task_created; data=" + Map.of("taskTitle", newTitle, "goalId", goalId, "taskId", task.getId()));
            notif.setTs(System.currentTimeMillis());
            notif.setPayload(Map.of(
                    "nav", "/goals",
                    "level", "info",
                    "ai", Map.of(
                            "userPrompt", "触发：task_created。用户新增任务。请基于数据生成 1-2 句中文关怀提醒，避免固定模板与重复句式，给一个最小动作建议。数据：" + Map.of(
                                    "taskTitle", newTitle,
                                    "goalId", goalId,
                                    "taskId", task.getId()
                            )
                    ),
                    "data", Map.of(
                            "taskTitle", newTitle,
                            "goalId", goalId,
                            "taskId", task.getId()
                    )
            ));
            rabbitTemplate.convertAndSend(RabbitMqConfig.NOTIFICATION_EXCHANGE, RabbitMqConfig.NOTIFICATION_ROUTING_KEY, notif);
        } catch (Exception e) {
            log.warn("任务创建通知发送失败, userId={}, goalId={}, taskTitle={}", userId, goalId, newTitle, e);
        }
        return task;
    }

    public static class DuplicateTaskException extends RuntimeException {
        private final Long existingTaskId;
        private final String existingTitle;

        public DuplicateTaskException(Long existingTaskId, String existingTitle) {
            super("任务已存在：" + (existingTitle == null ? "" : existingTitle.trim()) + (existingTaskId != null ? "（taskId=" + existingTaskId + "）" : ""));
            this.existingTaskId = existingTaskId;
            this.existingTitle = existingTitle;
        }

        public Long getExistingTaskId() { return existingTaskId; }
        public String getExistingTitle() { return existingTitle; }
    }

    private GoalTask findSimilarExistingTask(Long userId, Long goalId, String newTitle) {
        if (userId == null || goalId == null) return null;
        String a = normalizeTitle(newTitle);
        if (a.isBlank()) return null;

        List<GoalTask> candidates = goalTaskMapper.selectList(new LambdaQueryWrapper<GoalTask>()
                .eq(GoalTask::getUserId, userId)
                .eq(GoalTask::getGoalId, goalId)
                .orderByDesc(GoalTask::getId)
                .last("LIMIT 200"));
        if (candidates == null || candidates.isEmpty()) return null;

        for (GoalTask t : candidates) {
            if (t == null || t.getTitle() == null) continue;
            String b = normalizeTitle(t.getTitle());
            if (b.isBlank()) continue;
            if (a.equals(b)) return t;
            if (a.length() >= 4 && b.length() >= 4) {
                if (a.contains(b) || b.contains(a)) return t;
                double sim = TextSimilarity.bigramJaccard(a, b);
                if (sim >= 0.86d) return t;
            }
        }
        return null;
    }

    private String normalizeTitle(String s) {
        if (s == null) return "";
        String x = s.trim().toLowerCase();
        x = x.replaceAll("\\s+", "");
        x = x.replaceAll("[\\p{Punct}·•，。！？、；：()（）【】\\[\\]{}<>《》“”\"'`~@#$%^&*_+=|\\\\/]+", "");
        return x;
    }

    public List<GoalTask> listTasksByGoal(Long userId, Long goalId) {
        return goalTaskMapper.selectList(new LambdaQueryWrapper<GoalTask>()
                .eq(GoalTask::getUserId, userId)
                .eq(GoalTask::getGoalId, goalId)
                .orderByAsc(GoalTask::getId));
    }

    public List<GoalTaskDto> getPendingTasks(Long userId) {
        List<GoalTask> tasks = goalTaskMapper.selectList(new LambdaQueryWrapper<GoalTask>()
                .eq(GoalTask::getUserId, userId)
                .in(GoalTask::getStatus, 0, 1)
                .notLikeRight(GoalTask::getTitle, "[AI降级]"));

        return tasks.stream().map(t -> {
            GoalTaskDto dto = new GoalTaskDto();
            dto.setId(t.getId());
            dto.setUserId(t.getUserId());
            dto.setGoalId(t.getGoalId());
            dto.setParentId(t.getParentId());
            dto.setTitle(t.getTitle());
            dto.setDescription(t.getDescription());
            dto.setPriority(t.getPriority());
            dto.setStatus(t.getStatus());
            dto.setEstimatedMinutes(t.getEstimatedMinutes());
            return dto;
        }).collect(Collectors.toList());
    }

    public List<GoalTaskDto> getTasksByIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        List<GoalTask> tasks = goalTaskMapper.selectBatchIds(taskIds);
        if (tasks == null) {
            return List.of();
        }
        return tasks.stream().map(t -> {
            GoalTaskDto dto = new GoalTaskDto();
            dto.setId(t.getId());
            dto.setUserId(t.getUserId());
            dto.setGoalId(t.getGoalId());
            dto.setParentId(t.getParentId());
            dto.setTitle(t.getTitle());
            dto.setDescription(t.getDescription());
            dto.setPriority(t.getPriority());
            dto.setStatus(t.getStatus());
            dto.setEstimatedMinutes(t.getEstimatedMinutes());
            dto.setSubTasks(List.of());
            return dto;
        }).collect(Collectors.toList());
    }

    public void updateTaskStatus(Long taskId, Integer status) {
        GoalTask task = new GoalTask();
        task.setId(taskId);
        task.setStatus(status);
        goalTaskMapper.updateById(task);
    }

    public void moveTaskToGoal(Long userId, Long taskId, Long goalId) {
        GoalTask existing = goalTaskMapper.selectById(taskId);
        if (existing == null || existing.getUserId() == null || !existing.getUserId().equals(userId)) {
            throw new IllegalArgumentException("任务不存在");
        }
        GoalTask task = new GoalTask();
        task.setId(taskId);
        task.setGoalId(goalId);
        task.setParentId(null);
        task.setUpdatedAt(LocalDateTime.now());
        goalTaskMapper.updateById(task);
    }
}
