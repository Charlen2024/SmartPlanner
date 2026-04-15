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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
            String fullPrompt = systemPrompt + "\n用户目标：" + goalDescription;
            String response;
            try {
                response = CompletableFuture
                        .supplyAsync(() -> openAiCompatClient.complete(fullPrompt))
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
            for (GoalTaskDto taskDto : tasks) {
                saveTaskRecursive(userId, goalId, null, taskDto);
            }

            try {
                resourceClient.searchOnlineCourses(goalDescription);
            } catch (Exception e) {
                log.warn("资源检索/写入失败: {}", e.getMessage());
            }
            
            NotificationMessage notif = new NotificationMessage();
            notif.setUserId(userId);
            notif.setType("GOAL_TASK_READY");
            notif.setContent("你的学习目标【" + goalDescription + "】已完成 AI 任务拆解，可以开始排程啦！");
            rabbitTemplate.convertAndSend(RabbitMqConfig.NOTIFICATION_EXCHANGE, RabbitMqConfig.NOTIFICATION_ROUTING_KEY, notif);
            
        } catch (Exception e) {
            log.error("解析或保存目标失败", e);
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
        task.setStatus(0);
        goalTaskMapper.insert(task);

        if (taskDto.getSubTasks() != null) {
            for (GoalTaskDto subTask : taskDto.getSubTasks()) {
                saveTaskRecursive(userId, goalId, task.getId(), subTask);
            }
        }
    }
}

