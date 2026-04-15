package com.chao.goal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.client.ResourceClient;
import com.chao.common.dto.GoalDto;
import com.chao.common.dto.GoalTaskDto;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chao.goal.entity.Goal;
import com.chao.goal.entity.GoalTask;
import com.chao.goal.entity.UserJournal;
import com.chao.goal.mapper.GoalMapper;
import com.chao.goal.mapper.GoalTaskMapper;
import com.chao.goal.mapper.UserJournalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.chao.common.config.RabbitMqConfig;
import com.chao.common.dto.GoalAiTaskMessage;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoalService {

    private final OpenAiCompatClient openAiCompatClient;
    private final ResourceClient resourceClient;
    private final GoalMapper goalMapper;
    private final GoalTaskMapper goalTaskMapper;
    private final UserJournalMapper userJournalMapper;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;

    private static final String SYSTEM_PROMPT = """
        你是一个大学生学习规划专家。
        请将用户的学习目标拆解为 3-5 个具体的、可执行的子任务。
        每个任务需包含：title, description, estimatedMinutes, priority (0-2)。
        如果任务较复杂，可以包含 subTasks 列表（递归结构）。
        请直接返回 JSON 数组，不要有 Markdown 格式或额外文字。
        示例: [{"title":"学习基础","description":"阅读书籍第一章","estimatedMinutes":60,"priority":1, "subTasks": []}]
        """;

    public GoalDto createGoalAndStartAi(Long userId, String goalDescription) {
        String text = goalDescription == null ? "" : goalDescription.trim();
        Goal goal = new Goal();
        goal.setUserId(userId);
        goal.setTitle(text.length() > 80 ? text.substring(0, 80) : text);
        goal.setDescription(text);
        goal.setStatus(0);
        goal.setCreatedAt(LocalDateTime.now());
        goalMapper.insert(goal);

        GoalAiTaskMessage msg = new GoalAiTaskMessage();
        msg.setUserId(userId);
        msg.setGoalId(goal.getId());
        msg.setGoalDescription(text);
        msg.setSystemPrompt(SYSTEM_PROMPT);
        rabbitTemplate.convertAndSend(RabbitMqConfig.GOAL_EXCHANGE, RabbitMqConfig.GOAL_AI_ROUTING_KEY, msg);

        GoalDto dto = new GoalDto();
        dto.setId(goal.getId());
        dto.setUserId(goal.getUserId());
        dto.setTitle(goal.getTitle());
        dto.setDescription(goal.getDescription());
        dto.setStatus(goal.getStatus());
        dto.setDeadline(goal.getDeadline());
        dto.setCreatedAt(goal.getCreatedAt());
        return dto;
    }

    @Transactional
    public void regenerateTasks(Long userId, Long goalId, String feedback) {
        Goal goal = goalMapper.selectById(goalId);
        if (goal == null || goal.getUserId() == null || !goal.getUserId().equals(userId)) {
            throw new IllegalArgumentException("目标不存在");
        }
        goalTaskMapper.delete(new LambdaQueryWrapper<GoalTask>()
                .eq(GoalTask::getUserId, userId)
                .eq(GoalTask::getGoalId, goalId));

        String fb = feedback == null ? "" : feedback.trim();
        String prompt = SYSTEM_PROMPT;
        if (!fb.isBlank()) {
            prompt = prompt + "\n用户对上一版计划的意见：" + fb + "\n请根据意见重新生成任务，避免重复上一版表述。";
        }
        
        GoalAiTaskMessage msg = new GoalAiTaskMessage();
        msg.setUserId(userId);
        msg.setGoalId(goalId);
        msg.setGoalDescription(goal.getDescription());
        msg.setSystemPrompt(prompt);
        rabbitTemplate.convertAndSend(RabbitMqConfig.GOAL_EXCHANGE, RabbitMqConfig.GOAL_AI_ROUTING_KEY, msg);
    }

    public void parseAndSaveGoal(Long userId, String goalDescription) {
        createGoalAndStartAi(userId, goalDescription);
    }

    private String degradeTasks(String goalDescription) {
        String text = goalDescription == null ? "" : goalDescription.trim();
        return String.format("""
            [
              {"title":"[AI降级] 拆解目标","description":"目标：%s。AI 暂时不可用，请稍后重试。","estimatedMinutes":30,"priority":2,"subTasks":[]},
              {"title":"[AI降级] 收集资料","description":"先收集 3 个课程/文章链接，并写下学习大纲","estimatedMinutes":30,"priority":1,"subTasks":[]}
            ]
            """, escapeJson(text));
    }

    private String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void triggerResourceSearch(String topic) {
        try {
            resourceClient.searchOnlineCourses(topic);
        } catch (Exception e) {
            log.warn("资源检索/写入失败: {}", e.getMessage());
        }
    }

    private void saveTaskRecursive(Long userId, Long goalId, Long parentId, GoalTaskDto taskDto) {
        GoalTask task = new GoalTask();
        task.setUserId(userId);
        task.setGoalId(goalId);
        task.setParentId(parentId);
        task.setTitle(taskDto.getTitle());
        task.setDescription(taskDto.getDescription());
        task.setPriority(taskDto.getPriority());
        task.setEstimatedMinutes(taskDto.getEstimatedMinutes());
        task.setStatus(0); // 待启动
        goalTaskMapper.insert(task);

        if (taskDto.getSubTasks() != null) {
            for (GoalTaskDto subTask : taskDto.getSubTasks()) {
                saveTaskRecursive(userId, goalId, task.getId(), subTask);
            }
        }
    }

    public Goal createGoal(Long userId, String title, String description, LocalDateTime deadline) {
        Goal goal = new Goal();
        goal.setUserId(userId);
        goal.setTitle(title);
        goal.setDescription(description);
        goal.setDeadline(deadline);
        goal.setStatus(0);
        goal.setCreatedAt(LocalDateTime.now());
        goalMapper.insert(goal);
        return goal;
    }

    public List<Goal> listGoals(Long userId) {
        return goalMapper.selectList(new LambdaQueryWrapper<Goal>()
                .eq(Goal::getUserId, userId)
                .orderByDesc(Goal::getCreatedAt));
    }

    public Goal getGoal(Long goalId) {
        return goalMapper.selectById(goalId);
    }

    public void updateGoal(Long goalId, String title, String description, Integer status, LocalDateTime deadline) {
        Goal goal = new Goal();
        goal.setId(goalId);
        goal.setTitle(title);
        goal.setDescription(description);
        goal.setStatus(status);
        goal.setDeadline(deadline);
        goalMapper.updateById(goal);
    }

    public void deleteGoal(Long goalId) {
        goalMapper.deleteById(goalId);
        goalTaskMapper.delete(new LambdaQueryWrapper<GoalTask>().eq(GoalTask::getGoalId, goalId));
        userJournalMapper.delete(new LambdaQueryWrapper<UserJournal>().eq(UserJournal::getGoalId, goalId));
    }

    public GoalTask createTask(Long userId, Long goalId, Long parentId, String title, String description, Integer priority, Integer estimatedMinutes, LocalDateTime deadline) {
        GoalTask task = new GoalTask();
        task.setUserId(userId);
        task.setGoalId(goalId);
        task.setParentId(parentId);
        task.setTitle(title);
        task.setDescription(description);
        task.setPriority(priority);
        task.setEstimatedMinutes(estimatedMinutes);
        task.setDeadline(deadline);
        task.setStatus(0);
        goalTaskMapper.insert(task);
        return task;
    }

    public List<GoalTask> listTasksByGoal(Long userId, Long goalId) {
        return goalTaskMapper.selectList(new LambdaQueryWrapper<GoalTask>()
                .eq(GoalTask::getUserId, userId)
                .eq(GoalTask::getGoalId, goalId)
                .orderByAsc(GoalTask::getId));
    }

    public List<UserJournal> listJournals(Long userId, Long goalId) {
        LambdaQueryWrapper<UserJournal> qw = new LambdaQueryWrapper<UserJournal>()
                .eq(UserJournal::getUserId, userId)
                .orderByDesc(UserJournal::getCreatedAt);
        if (goalId != null) {
            qw.eq(UserJournal::getGoalId, goalId);
        }
        return userJournalMapper.selectList(qw);
    }

    public List<GoalTaskDto> getPendingTasks(Long userId) {
        List<GoalTask> tasks = goalTaskMapper.selectList(new LambdaQueryWrapper<GoalTask>()
                .eq(GoalTask::getUserId, userId)
                .in(GoalTask::getStatus, 0, 1));
        
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

    /**
     * 更新任务状态
     */
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

    /**
     * 保存用户随笔
     */
    public void saveJournal(Long userId, Long goalId, String content, String mood) {
        UserJournal journal = new UserJournal();
        journal.setUserId(userId);
        journal.setGoalId(goalId);
        journal.setContent(content);
        journal.setMood(mood);
        journal.setCreatedAt(LocalDateTime.now());
        userJournalMapper.insert(journal);
    }
}
