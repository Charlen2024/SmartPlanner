package com.chao.goal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.client.ResourceClient;
import com.chao.common.dto.GoalTaskDto;
import com.chao.goal.entity.GoalTask;
import com.chao.goal.mapper.GoalTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.chao.common.config.RabbitMqConfig;
import com.chao.common.dto.GoalAiTaskMessage;
import com.chao.common.dto.NotificationMessage;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoalAiWorker {
    private final OpenAiCompatClient openAiCompatClient;
    private final ResourceClient resourceClient;
    private final GoalTaskMapper goalTaskMapper;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = RabbitMqConfig.GOAL_AI_QUEUE)
    @Transactional
    public void handleGoalAiTask(GoalAiTaskMessage message) {
        Long userId = message.getUserId();
        Long goalId = message.getGoalId();
        String goalDescription = message.getGoalDescription();
        String systemPrompt = message.getSystemPrompt();

        log.info("MQ接收到任务，开始拆解用户 {} 的目标: {}", userId, goalDescription);
        try {
            // Notify frontend: decomposition started
            sendDecomposeProgress(userId, "GOAL_DECOMPOSE_STARTED", goalDescription, "INTENT", 5, "正在分析目标意图", 0, List.of());

            String response;
            try {
                String sys = systemPrompt == null ? "" : systemPrompt;
                String user = goalDescription == null ? "" : goalDescription;
                response = CompletableFuture
                        .supplyAsync(() -> openAiCompatClient.complete(sys, user))
                        .orTimeout(90, TimeUnit.SECONDS)
                        .join();
                log.info("AI 拆解结果: {}", response);
            } catch (Exception aiEx) {
                log.error("AI 调用失败或超时: {}", aiEx.getMessage());
                response = """
                    [
                      {"title":"[AI降级] 拆解目标","description":"AI 暂时不可用，请稍后重试。","estimatedMinutes":30,"priority":2,"subTasks":[]},
                      {"title":"[AI降级] 收集资料","description":"先收集 3 个课程/文章链接，并写下学习大纲","estimatedMinutes":30,"priority":1,"subTasks":[]}
                    ]
                    """;
            }

            List<GoalTaskDto> tasks = objectMapper.readValue(response, new TypeReference<List<GoalTaskDto>>() {});
            tasks = tasks == null ? List.of() : tasks.stream().filter(Objects::nonNull).collect(Collectors.toList());
            tasks = sanitizeTasks(tasks);

            if (tasks.isEmpty()) {
                tasks = objectMapper.readValue("""
                    [
                      {"title":"[AI降级] 拆解目标","description":"模型返回结果不符合约束，请稍后重试。","estimatedMinutes":30,"priority":2,"subTasks":[]},
                      {"title":"[AI降级] 收集资料","description":"先收集 3 个课程/文章链接，并写下学习大纲","estimatedMinutes":30,"priority":1,"subTasks":[]}
                    ]
                    """, new TypeReference<List<GoalTaskDto>>() {});
            }

            // Notify frontend: tasks generated (after fallback, so count is never 0)
            List<String> taskTitles = tasks.stream()
                    .map(GoalTaskDto::getTitle)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            sendDecomposeProgress(userId, "GOAL_DECOMPOSE_PROGRESS", goalDescription, "LLM", 50, "AI正在拆解生成任务…", tasks.size(), taskTitles);

            boolean degraded = tasks.stream().anyMatch(t -> {
                String title = t.getTitle();
                return title != null && title.startsWith("[AI降级]");
            });

            List<GoalTask> existing = goalTaskMapper.selectList(new LambdaQueryWrapper<GoalTask>()
                    .eq(GoalTask::getUserId, userId)
                    .eq(GoalTask::getGoalId, goalId));
            existing = existing == null ? List.of() : existing;

            boolean hasRealExisting = existing.stream().anyMatch(t -> {
                String title = t.getTitle();
                return title != null && !title.startsWith("[AI降级]");
            });

            boolean hasDegradedExisting = existing.stream().anyMatch(t -> {
                String title = t.getTitle();
                return title != null && title.startsWith("[AI降级]");
            });

            if (degraded) {
                if (hasRealExisting || hasDegradedExisting) {
                    log.info("检测到降级任务且已存在任务记录（real={} degraded={}），跳过写入，goalId={}", hasRealExisting, hasDegradedExisting, goalId);
                } else {
                    for (GoalTaskDto taskDto : tasks) {
                        saveTaskRecursive(userId, goalId, null, taskDto, true);
                    }
                }
            } else {
                if (hasRealExisting) {
                    log.info("检测到真实任务且已存在真实任务记录，跳过重复写入，goalId={}", goalId);
                } else {
                    if (!existing.isEmpty()) {
                        goalTaskMapper.delete(new LambdaQueryWrapper<GoalTask>()
                                .eq(GoalTask::getUserId, userId)
                                .eq(GoalTask::getGoalId, goalId));
                    }
                    for (GoalTaskDto taskDto : tasks) {
                        saveTaskRecursive(userId, goalId, null, taskDto, false);
                    }
                }
            }

            // Notify: saving to database
            sendDecomposeProgress(userId, "GOAL_DECOMPOSE_SAVING", goalDescription, "SAVING", 75, "正在保存任务并触发资源检索…", tasks.size(), taskTitles);

            // 为目标主题做 AI 资源推荐并写入库
            try {
                resourceClient.searchOnlineCourses(goalDescription);
            } catch (Exception e) {
                log.warn("资源检索/写入失败: {}", e.getMessage());
            }

            // 按每个任务标题逐条触发爬虫，动态扩充资源库
            List<String> crawlTopics = tasks.stream()
                    .map(GoalTaskDto::getTitle)
                    .filter(t -> t != null && !t.isBlank() && !t.startsWith("[AI降级]"))
                    .distinct()
                    .collect(Collectors.toList());
            if (!crawlTopics.isEmpty()) {
                log.info("触发 {} 个主题的爬取: {}", crawlTopics.size(), crawlTopics);
            }
            for (String topic : crawlTopics) {
                try {
                    resourceClient.crawlTopic(topic);
                } catch (Exception e) {
                    log.warn("爬取触发失败 topic={}: {}", topic, e.getMessage());
                }
            }

            NotificationMessage notif = new NotificationMessage();
            notif.setUserId(userId);
            notif.setType("GOAL_TASK_READY");
            notif.setContent("AI任务拆解已完成！");
            java.util.Map<String, Object> readyPayload = new java.util.LinkedHashMap<>();
            readyPayload.put("stage", "DONE");
            readyPayload.put("progress", 100);
            readyPayload.put("message", "拆解完成，共生成 " + tasks.size() + " 个任务");
            readyPayload.put("nav", "/schedule");
            readyPayload.put("level", "success");
            readyPayload.put("taskCount", tasks.size());
            readyPayload.put("taskTitles", taskTitles);
            readyPayload.put("goal", goalDescription);
            readyPayload.put("ai", java.util.Map.of(
                    "userPrompt", "触发：goal_task_ready。目标任务拆解已完成。请生成一句简短提醒（不固定模板），引导用户去日程/排程查看。数据：" + java.util.Map.of(
                            "goal", goalDescription
                    )
            ));
            readyPayload.put("data", java.util.Map.of(
                    "goal", goalDescription
            ));
            notif.setPayload(readyPayload);
            rabbitTemplate.convertAndSend(RabbitMqConfig.NOTIFICATION_EXCHANGE, RabbitMqConfig.NOTIFICATION_ROUTING_KEY, notif);
            
        } catch (Exception e) {
            log.error("目标拆解失败: userId={}, goalId={}, error={}", userId, goalId, e.getMessage());
            sendDecomposeProgress(userId, "GOAL_DECOMPOSE_FAILED", goalDescription, "FAILED", 100,
                    "拆解失败: " + (e.getMessage() != null ? e.getMessage() : "服务异常"),
                    0, List.of());
        }
    }

    private List<GoalTaskDto> sanitizeTasks(List<GoalTaskDto> input) {
        if (input == null || input.isEmpty()) return List.of();
        return input.stream()
                .filter(Objects::nonNull)
                .map(this::sanitizeOne)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private GoalTaskDto sanitizeOne(GoalTaskDto t) {
        String title = t.getTitle() == null ? "" : t.getTitle().trim();
        if (title.isBlank()) return null;
        if (isForbiddenTitle(title)) return null;
        if (containsDateOrTime(title)) return null;

        String desc = t.getDescription() == null ? "" : t.getDescription().trim();
        if (containsDateOrTime(desc)) return null;
        if (isForbiddenDescription(desc)) return null;

        Integer minutes = t.getEstimatedMinutes();
        if (minutes == null || minutes < 15 || minutes > 240) {
            minutes = 45;
        }

        Integer pr = t.getPriority();
        if (pr == null) pr = 1;
        if (pr < 0) pr = 0;
        if (pr > 2) pr = 2;

        GoalTaskDto out = new GoalTaskDto();
        out.setTitle(title);
        out.setDescription(desc);
        out.setEstimatedMinutes(minutes);
        out.setPriority(pr);
        if (t.getSubTasks() != null && !t.getSubTasks().isEmpty()) {
            List<GoalTaskDto> subs = t.getSubTasks().stream()
                    .filter(Objects::nonNull)
                    .map(this::sanitizeOne)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            out.setSubTasks(subs);
        } else {
            out.setSubTasks(List.of());
        }
        return out;
    }

    private boolean isForbiddenTitle(String title) {
        String s = title.replace(" ", "");
        return s.contains("制定学习计划") || s.contains("生成学习计划") || s.contains("安排学习计划")
                || s.contains("安排日程") || s.contains("排程") || s.contains("设置提醒") || s.contains("整理计划");
    }

    private boolean isForbiddenDescription(String desc) {
        String s = desc.replace(" ", "");
        return s.contains("制定学习计划") || s.contains("生成学习计划") || s.contains("安排日程") || s.contains("排程") || s.contains("设置提醒");
    }

    private boolean containsDateOrTime(String text) {
        if (text == null || text.isBlank()) return false;
        String s = text;
        return s.matches(".*\\d{4}-\\d{2}-\\d{2}.*") || s.matches(".*\\b\\d{1,2}:\\d{2}\\b.*");
    }

    private void sendDecomposeProgress(Long userId, String type, String goalDescription, String stage, int progress, String message, int taskCount, List<String> taskTitles) {
        try {
            NotificationMessage notif = new NotificationMessage();
            notif.setUserId(userId);
            notif.setType(type);
            notif.setContent(message);
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("stage", stage);
            payload.put("progress", progress);
            payload.put("message", message);
            payload.put("goal", goalDescription);
            payload.put("taskCount", taskCount);
            payload.put("taskTitles", taskTitles);
            notif.setPayload(payload);
            rabbitTemplate.convertAndSend(RabbitMqConfig.NOTIFICATION_EXCHANGE, RabbitMqConfig.NOTIFICATION_ROUTING_KEY, notif);
        } catch (Exception e) {
            log.warn("发送拆解进度通知失败: {}", e.getMessage());
        }
    }

    private void saveTaskRecursive(Long userId, Long goalId, Long parentId, GoalTaskDto taskDto, boolean degraded) {
        GoalTask task = new GoalTask();
        task.setUserId(userId);
        task.setGoalId(goalId);
        task.setParentId(parentId);
        task.setTitle(taskDto.getTitle());
        task.setDescription(taskDto.getDescription());
        task.setPriority(taskDto.getPriority());
        task.setEstimatedMinutes(taskDto.getEstimatedMinutes());
        task.setStatus(degraded ? 9 : 0);
        goalTaskMapper.insert(task);

        if (taskDto.getSubTasks() != null) {
            for (GoalTaskDto subTask : taskDto.getSubTasks()) {
                saveTaskRecursive(userId, goalId, task.getId(), subTask, degraded);
            }
        }
    }
}
