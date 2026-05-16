package com.chao.resource.service;

import com.chao.common.client.ResourceClient;
import com.chao.common.config.RabbitMqConfig;
import com.chao.common.dto.NotificationMessage;
import com.chao.common.dto.ResourceAdviceJobMessage;
import com.chao.common.dto.ResourceAdviceJobStartRequest;
import com.chao.common.dto.ResourceAdviceJobStartResponse;
import com.chao.common.dto.ResourceAdviceJobStatusResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class ResourceAdviceJobService {
    private static final long EXPIRE_SECONDS = 6 * 60 * 60;

    private final ResourceService resourceService;
    private final RabbitTemplate rabbitTemplate;
    private final Executor executor;
    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

    public ResourceAdviceJobService(ResourceService resourceService, RabbitTemplate rabbitTemplate, @Qualifier("applicationTaskExecutor") Executor executor) {
        this.resourceService = resourceService;
        this.rabbitTemplate = rabbitTemplate;
        this.executor = executor;
    }

    public ResourceAdviceJobStartResponse start(Long userId, ResourceAdviceJobStartRequest request) {
        if (userId == null) throw new IllegalArgumentException("userId required");
        String topic = request != null ? request.getTopic() : null;
        if (topic == null || topic.trim().isBlank()) throw new IllegalArgumentException("topic required");

        String jobId = UUID.randomUUID().toString();
        JobState state = new JobState();
        state.jobId = jobId;
        state.userId = userId;
        state.topic = topic.trim();
        state.status = "RUNNING";
        state.stage = "QUEUED";
        state.progress = 1;
        state.message = "已进入队列";
        state.createdAt = Instant.now().getEpochSecond();
        state.updatedAt = state.createdAt;
        jobs.put(jobId, state);

        executor.execute(() -> dispatchOrRunInline(state));

        ResourceAdviceJobStartResponse resp = new ResourceAdviceJobStartResponse();
        resp.setJobId(jobId);
        return resp;
    }

    private void dispatchOrRunInline(JobState state) {
        if (state == null) return;
        if (!"RUNNING".equals(state.status)) return;
        update(state, "RUNNING", "DISPATCH", 3, "正在派发任务");
        try {
            ResourceAdviceJobMessage msg = new ResourceAdviceJobMessage();
            msg.setJobId(state.jobId);
            msg.setUserId(state.userId);
            msg.setTopic(state.topic);
            rabbitTemplate.convertAndSend(RabbitMqConfig.RESOURCE_EXCHANGE, RabbitMqConfig.RESOURCE_ADVICE_ROUTING_KEY, msg);
            update(state, "RUNNING", "QUEUED", 5, "已进入队列");
        } catch (Exception mqEx) {
            log.warn("派发 MQ 失败，改为本地异步执行: userId={}, jobId={}, err={}", state.userId, state.jobId, mqEx.getMessage());
            runInline(state);
        }
    }

    private void runInline(JobState state) {
        if (state == null) return;
        if (!"RUNNING".equals(state.status)) return;
        update(state, "RUNNING", "RUNNING", 10, "正在生成建议与资源列表");
        try {
            ResourceClient.ResourceAdviceResponse resp = resourceService.searchResourcesWithAdvice(state.topic);
            state.result = resp;
            update(state, "DONE", "DONE", 100, "已生成");
            sendNotification(state.userId, "RESOURCE_ADVICE_DONE", "资源推荐已生成，快去查看吧！");
        } catch (Exception e) {
            state.error = e.getMessage() != null ? e.getMessage() : "服务异常";
            update(state, "FAILED", "FAILED", 100, "生成失败");
            log.warn("资源检索 advice job 失败: userId={}, jobId={}, error={}", state.userId, state.jobId, state.error);
            sendNotification(state.userId, "RESOURCE_ADVICE_FAILED", "资源推荐生成失败：" + state.error);
        }
    }

    public ResourceAdviceJobStatusResponse status(Long userId, String jobId) {
        JobState state = jobs.get(jobId);
        if (state == null || state.userId == null || !state.userId.equals(userId)) {
            throw new IllegalArgumentException("job 不存在或无权限");
        }
        long now = Instant.now().getEpochSecond();
        if (now - state.createdAt > EXPIRE_SECONDS) {
            jobs.remove(jobId);
            throw new IllegalArgumentException("job 已过期");
        }
        return state.toDto();
    }

    @RabbitListener(queues = RabbitMqConfig.RESOURCE_ADVICE_QUEUE)
    public void handle(ResourceAdviceJobMessage message) {
        if (message == null || message.getJobId() == null) return;
        JobState state = jobs.get(message.getJobId());
        if (state == null) return;
        if (state.userId == null || message.getUserId() == null || !state.userId.equals(message.getUserId())) return;
        if (!"RUNNING".equals(state.status)) return;
        runInline(state);
    }

    private void sendNotification(Long userId, String type, String content) {
        try {
            NotificationMessage notif = new NotificationMessage();
            notif.setUserId(userId);
            notif.setType(type);
            notif.setContent(content);
            rabbitTemplate.convertAndSend(RabbitMqConfig.NOTIFICATION_EXCHANGE, RabbitMqConfig.NOTIFICATION_ROUTING_KEY, notif);
        } catch (Exception ignored) {
        }
    }

    private void update(JobState state, String status, String stage, int progress, String message) {
        state.status = status;
        state.stage = stage;
        state.progress = progress;
        state.message = message;
        state.updatedAt = Instant.now().getEpochSecond();
    }

    @Data
    static class JobState {
        private String jobId;
        private Long userId;
        private String topic;
        private String status;
        private String stage;
        private Integer progress;
        private String message;
        private String error;
        private ResourceClient.ResourceAdviceResponse result;
        private long createdAt;
        private long updatedAt;

        ResourceAdviceJobStatusResponse toDto() {
            ResourceAdviceJobStatusResponse dto = new ResourceAdviceJobStatusResponse();
            dto.setJobId(jobId);
            dto.setStatus(status);
            dto.setStage(stage);
            dto.setProgress(progress);
            dto.setMessage(message);
            dto.setError(error);
            dto.setResult(result);
            return dto;
        }
    }
}
