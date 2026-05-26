package com.chao.goal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.client.ResourceClient;
import com.chao.common.client.ScheduleClient;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.chao.common.config.RabbitMqConfig;
import com.chao.common.dto.GoalAiTaskMessage;
import com.chao.common.dto.NotificationMessage;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoalService {

    private final OpenAiCompatClient openAiCompatClient;
    private final ResourceClient resourceClient;
    private final ScheduleClient scheduleClient;
    private final GoalMapper goalMapper;
    private final GoalTaskMapper goalTaskMapper;
    private final UserJournalMapper userJournalMapper;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;

    private static final String SYSTEM_PROMPT = """
        你是“学习任务拆解”专家，目标是把学习目标拆成可以直接执行的学习任务（而不是计划/排程/管理动作）。

        输出要求（必须严格遵守）：
        1) 只输出 JSON 数组，不要 Markdown，不要解释性文字，不要代码块。
        2) 数组元素为任务对象：{title, description, estimatedMinutes, priority, subTasks}。
           - title: 8-20 字，必须包含学习内容关键词 + 动作动词（如：阅读/练习/总结/复盘/背诵/听力/口语/写作/刷题）。
           - description: 1-2 句，给出清晰的完成标准（可交付物），不要写“制定计划/安排时间/生成学习计划”。
           - estimatedMinutes: 15-120 的整数。
           - priority: 0-2（0 低，1 中，2 高）。
           - subTasks: 子任务数组（可为空）。如使用子任务，则父任务 estimatedMinutes 为子任务总和或近似总和。

        强约束（禁止出现）：
        - 禁止把“制定学习计划/安排日程/排程/设置提醒/写计划/整理计划/检查进度”当成任务输出。
        - 禁止在 title/description 中出现具体日期时间（如 2026-05-22、08:00-08:30），时间由排程模块决定。
        - 禁止输出空泛任务（如“学习一下/了解一下/做点练习”）。

        数量建议：
        - 默认输出 5-8 个任务；若目标很小可输出 3-5 个。

        示例（仅示意格式，不要照抄内容）：
        [{"title":"阅读：第一章核心概念","description":"阅读教材第1章并用100字总结3个概念","estimatedMinutes":60,"priority":2,"subTasks":[]}]
        """;

    public GoalDto createGoalAndStartAi(Long userId, String goalDescription) {
        String text = goalDescription == null ? "" : goalDescription.trim();//去除首尾的空白字符
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

    private void saveTaskRecursive/*递归的意思*/(Long userId, Long goalId, Long parentId, GoalTaskDto taskDto) {
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
        Goal goal = goalMapper.selectById(goalId);
        Long userId = goal != null ? goal.getUserId() : null;
        List<Long> taskIds = goalTaskMapper.selectList(new LambdaQueryWrapper<GoalTask>()
                        .eq(GoalTask::getGoalId, goalId))
                .stream()
                .map(GoalTask::getId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .collect(Collectors.toList());
        if (userId != null && userId > 0 && !taskIds.isEmpty()) {
            try {
                scheduleClient.deleteTaskSchedulesByTaskIds(userId, taskIds);
            } catch (Exception ignored) {
            }
        }
        goalMapper.deleteById(goalId);
        goalTaskMapper.delete(new LambdaQueryWrapper<GoalTask>().eq(GoalTask::getGoalId, goalId));
        userJournalMapper.delete(new LambdaQueryWrapper<UserJournal>().eq(UserJournal::getGoalId, goalId));
    }

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
        } catch (Exception ignored) {
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

        public Long getExistingTaskId() {
            return existingTaskId;
        }

        public String getExistingTitle() {
            return existingTitle;
        }
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
                double sim = bigramJaccard(a, b);
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

    private double bigramJaccard(String a, String b) {
        Set<String> sa = bigrams(a);
        Set<String> sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0d;
        int inter = 0;
        for (String g : sa) {
            if (sb.contains(g)) inter++;
        }
        int union = sa.size() + sb.size() - inter;
        return union <= 0 ? 0d : (inter * 1.0d / union);
    }

    private Set<String> bigrams(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        String x = s.trim();
        if (x.isEmpty()) return out;
        if (x.length() == 1) {
            out.add(x);
            return out;
        }
        for (int i = 0; i < x.length() - 1; i++) {
            out.add(x.substring(i, i + 2));
        }
        return out;
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

    public void deleteJournal(Long userId, Long journalId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("未授权");
        }
        if (journalId == null || journalId <= 0) {
            throw new IllegalArgumentException("随笔不存在");
        }
        UserJournal existing = userJournalMapper.selectById(journalId);
        if (existing == null || existing.getUserId() == null || !existing.getUserId().equals(userId)) {
            throw new IllegalArgumentException("随笔不存在");
        }
        userJournalMapper.deleteById(journalId);
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

        try {
            String c = content != null ? content : "";
            String m = mood != null ? mood : "";
            String combined = (c + "\n" + m).trim();
            if (!combined.isBlank() && containsNegativeWords(combined)) {
                NotificationMessage notif = new NotificationMessage();
                notif.setUserId(userId);
                notif.setType("AGENT_REMINDER");
                notif.setContent("trigger=journal_negative; data=" + Map.of("goalId", goalId, "journalId", journal.getId()));
                notif.setTs(System.currentTimeMillis());
                notif.setPayload(Map.of(
                        "nav", "/journals",
                        "level", "warning",
                        "ai", Map.of(
                                "userPrompt", "触发：journal_negative。用户随笔/心情中出现消极词。请基于原文片段与数据生成 1-2 句安慰提醒，避免固定模板与重复句式，提出一个可执行的小建议。数据：" + Map.of(
                                        "goalId", goalId,
                                        "journalId", journal.getId(),
                                        "excerpt", combined.length() > 120 ? combined.substring(0, 120) : combined
                                )
                        ),
                        "data", Map.of(
                                "goalId", goalId,
                                "journalId", journal.getId()
                        )
                ));
                rabbitTemplate.convertAndSend(RabbitMqConfig.NOTIFICATION_EXCHANGE, RabbitMqConfig.NOTIFICATION_ROUTING_KEY, notif);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean containsNegativeWords(String text) {
        String t = text == null ? "" : text;
        if (t.isBlank()) return false;
        String[] words = new String[]{
                "焦虑", "压力", "崩溃", "很累", "抑郁", "难受", "烦", "痛苦", "失眠",
                "不想", "没动力", "坚持不下去", "摆烂", "绝望", "害怕", "内耗"
        };
        for (String w : words) {
            if (w != null && !w.isBlank() && t.contains(w)) return true;
        }
        return false;
    }
    public java.util.List<String> getDistinctGoalTopics() {
        java.util.List<com.chao.goal.entity.Goal> goals = goalMapper.selectList(null);
        if (goals == null || goals.isEmpty()) return java.util.List.of();
        return goals.stream()
                .map(com.chao.goal.entity.Goal::getTitle)
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.toList());
    }
}
