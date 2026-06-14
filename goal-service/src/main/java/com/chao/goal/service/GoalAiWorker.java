package com.chao.goal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.client.ResourceClient;
import com.chao.common.dto.CourseResourceDto;
import com.chao.common.dto.GoalTaskDto;
import com.chao.common.dto.ResourceAdviceResult;
import com.chao.goal.config.AiConfig;
import com.chao.goal.entity.GoalTask;
import com.chao.goal.mapper.GoalTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
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
    private final RedissonClient redissonClient;
    private final AiResponseParser aiResponseParser;
    private final AiConfig aiConfig;
    private final TransactionTemplate transactionTemplate;

    @RabbitListener(queues = RabbitMqConfig.GOAL_AI_QUEUE)
    public void handleGoalAiTask(GoalAiTaskMessage message) {
        Long userId = message.getUserId();
        Long goalId = message.getGoalId();
        String goalDescription = message.getGoalDescription();
        String systemPrompt = message.getSystemPrompt();

        log.info("MQ接收到任务，开始拆解用户 {} 的目标: {}", userId, goalDescription);
        try {
            sendDecomposeProgress(userId, "GOAL_DECOMPOSE_STARTED", goalDescription, "INTENT", 5, "正在分析目标意图", 0, List.of());

            String response;
            try {
                String sys = systemPrompt == null ? "" : systemPrompt;
                String user = goalDescription == null ? "" : goalDescription;
                int timeout = aiConfig.getDecomposeTimeoutSeconds();
                response = CompletableFuture
                        .supplyAsync(() -> openAiCompatClient.complete(sys, user))
                        .orTimeout(timeout, TimeUnit.SECONDS)
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
            tasks = aiResponseParser.sanitizeTasks(tasks);

            if (tasks.isEmpty()) {
                tasks = objectMapper.readValue("""
                    [
                      {"title":"[AI降级] 拆解目标","description":"模型返回结果不符合约束，请稍后重试。","estimatedMinutes":30,"priority":2,"subTasks":[]},
                      {"title":"[AI降级] 收集资料","description":"先收集 3 个课程/文章链接，并写下学习大纲","estimatedMinutes":30,"priority":1,"subTasks":[]}
                    ]
                    """, new TypeReference<List<GoalTaskDto>>() {});
            }

            List<String> taskTitles = tasks.stream()
                    .map(GoalTaskDto::getTitle)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            sendDecomposeProgress(userId, "GOAL_DECOMPOSE_PROGRESS", goalDescription, "LLM", 50, "AI正在拆解生成任务…", tasks.size(), taskTitles);

            boolean degraded = tasks.stream().anyMatch(t -> {
                String title = t.getTitle();
                return title != null && title.startsWith("[AI降级]");
            });

            final List<GoalTaskDto> finalTasks = tasks;

            List<GoalTask> savedTasks = transactionTemplate.execute(status -> {
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

                List<GoalTask> saved = new ArrayList<>();
                if (degraded) {
                    if (hasRealExisting || hasDegradedExisting) {
                        log.info("检测到降级任务且已存在任务记录（real={} degraded={}），跳过写入，goalId={}", hasRealExisting, hasDegradedExisting, goalId);
                    } else {
                        for (GoalTaskDto taskDto : finalTasks) {
                            saved.addAll(saveTaskRecursive(userId, goalId, null, taskDto, true));
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
                        for (GoalTaskDto taskDto : finalTasks) {
                            saved.addAll(saveTaskRecursive(userId, goalId, null, taskDto, false));
                        }
                    }
                }
                return saved;
            });

            // Transaction already committed via transactionTemplate.execute above
            final int finalTaskCount = tasks.size();
            final List<String> finalTaskTitles = taskTitles;
            final String finalGoalDesc = goalDescription;

            // 先发送 DONE 通知，资源检索/爬虫异步执行不阻塞用户反馈
            sendDecomposeProgress(userId, "GOAL_DECOMPOSE_DONE", goalDescription, "DONE", 100,
                    "拆解完成，共生成 " + finalTaskCount + " 个任务", finalTaskCount, finalTaskTitles);
            NotificationMessage notif = new NotificationMessage();
            notif.setUserId(userId);
            notif.setType("GOAL_TASK_READY");
            notif.setContent("AI任务拆解已完成！");
            java.util.Map<String, Object> readyPayload = new java.util.LinkedHashMap<>();
            readyPayload.put("stage", "DONE");
            readyPayload.put("progress", 100);
            readyPayload.put("message", "拆解完成，共生成 " + finalTaskCount + " 个任务");
            readyPayload.put("nav", "/schedule");
            readyPayload.put("level", "success");
            readyPayload.put("taskCount", finalTaskCount);
            readyPayload.put("taskTitles", finalTaskTitles);
            readyPayload.put("goal", finalGoalDesc);
            readyPayload.put("ai", java.util.Map.of(
                    "userPrompt", "触发：goal_task_ready。目标任务拆解已完成。请生成一句简短提醒（不固定模板），引导用户去日程/排程查看。数据：" + java.util.Map.of(
                            "goal", finalGoalDesc
                    )
            ));
            readyPayload.put("data", java.util.Map.of(
                    "goal", finalGoalDesc
            ));
            notif.setPayload(readyPayload);
            rabbitTemplate.convertAndSend(RabbitMqConfig.NOTIFICATION_EXCHANGE, RabbitMqConfig.NOTIFICATION_ROUTING_KEY, notif);

            // 资源检索和爬虫异步执行，不阻塞 DONE 通知和 MQ ACK
            final Long finalUserId = userId;
            CompletableFuture.runAsync(() -> {
                try {
                    resourceClient.searchOnlineCourses(finalGoalDesc);
                } catch (Exception e) {
                    log.warn("资源检索/写入失败: {}", e.getMessage());
                }
                List<String> crawlTopics = finalTasks.stream()
                        .map(GoalTaskDto::getTitle)
                        .filter(t -> t != null && !t.isBlank() && !t.startsWith("[AI降级]"))
                        .distinct()
                        .collect(Collectors.toList());
                if (!crawlTopics.isEmpty()) {
                    log.info("触发 {} 个主题的爬取: {}", crawlTopics.size(), crawlTopics);
                }
                for (String topic : crawlTopics) {
                    try {
                        resourceClient.crawlTopic(topic, finalUserId);
                    } catch (Exception e) {
                        log.warn("爬取触发失败 topic={}: {}", topic, e.getMessage());
                    }
                }
                if (!savedTasks.isEmpty()) {
                    prefetchTaskResources(finalUserId, savedTasks);
                }
            });

        } catch (Exception e) {
            log.error("目标拆解失败: userId={}, goalId={}, error={}", userId, goalId, e.getMessage());
            sendDecomposeProgress(userId, "GOAL_DECOMPOSE_FAILED", goalDescription, "FAILED", 100,
                    "拆解失败: " + (e.getMessage() != null ? e.getMessage() : "服务异常"),
                    0, List.of());
        }
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

    private List<GoalTask> saveTaskRecursive(Long userId, Long goalId, Long parentId, GoalTaskDto taskDto, boolean degraded) {
        List<GoalTask> saved = new ArrayList<>();
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
        saved.add(task);

        if (taskDto.getSubTasks() != null) {
            for (GoalTaskDto subTask : taskDto.getSubTasks()) {
                saved.addAll(saveTaskRecursive(userId, goalId, task.getId(), subTask, degraded));
            }
        }
        return saved;
    }

    private static final String TASK_RESOURCES_KEY_PREFIX = "sp:task:resources:v2:";

    private void prefetchTaskResources(Long userId, List<GoalTask> tasks) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (GoalTask task : tasks) {
            String title = task.getTitle();
            if (title == null || title.isBlank() || title.startsWith("[AI降级]")) {
                continue;
            }
            Long taskId = task.getId();
            if (taskId == null) continue;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    var result = resourceClient.searchOnlineCoursesWithAdvice(title);
                    ResourceAdviceResult r = result != null ? result.getData() : null;
                    if (r == null || r.getResources() == null || r.getResources().isEmpty()) {
                        log.debug("prefetch: no resources for taskId={}, title={}", taskId, title);
                        return;
                    }
                    List<CourseResourceDto> dtos = r.getResources().stream()
                            .map(res -> {
                                CourseResourceDto dto = new CourseResourceDto();
                                dto.setTopic(title);
                                dto.setTitle(res.getTitle());
                                dto.setPlatform(res.getPlatform());
                                dto.setSourceUrl(res.getUrl());
                                dto.setContentSummary(res.getSummary());
                                return dto;
                            })
                            .collect(Collectors.toList());
                    String json = objectMapper.writeValueAsString(dtos);
                    RBucket<String> bucket = redissonClient.getBucket(TASK_RESOURCES_KEY_PREFIX + taskId);
                    bucket.set(json);
                    bucket.expire(Duration.ofDays(2));
                    log.info("prefetch: cached {} resources for taskId={}, title={}", dtos.size(), taskId, title);
                } catch (Exception e) {
                    log.warn("prefetch failed for taskId={}, title={}: {}", taskId, title, e.getMessage());
                }
            }));
        }
        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(120, TimeUnit.SECONDS)
                    .exceptionally(ex -> { log.warn("prefetch batch timed out or failed: {}", ex.getMessage()); return null; });
        }
    }
}
