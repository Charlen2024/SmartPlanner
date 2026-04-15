package com.chao.schedule.service;

import com.chao.common.dto.DailyPlanCommitRequest;
import com.chao.common.dto.DailyPlanCommitResponse;
import com.chao.common.dto.DailyPlanJobStartRequest;
import com.chao.common.dto.DailyPlanJobStartResponse;
import com.chao.common.dto.DailyPlanJobStatusResponse;
import lombok.Data;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import com.chao.common.config.RabbitMqConfig;
import com.chao.common.dto.NotificationMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class DailyPlanJobService {
    private static final long EXPIRE_SECONDS = 6 * 60 * 60;

    private final ScheduleService scheduleService;
    private final Executor executor;
    private final RabbitTemplate rabbitTemplate;
    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

    public DailyPlanJobService(ScheduleService scheduleService, @Qualifier("applicationTaskExecutor") Executor executor, RabbitTemplate rabbitTemplate) {
        this.scheduleService = scheduleService;
        this.executor = executor;
        this.rabbitTemplate = rabbitTemplate;
    }

    public DailyPlanJobStartResponse start(Long userId, DailyPlanJobStartRequest request) {
        String jobId = UUID.randomUUID().toString();
        JobState state = new JobState();
        state.jobId = jobId;
        state.userId = userId;
        state.status = "RUNNING";
        state.stage = "PREPARE";
        state.progress = 1;
        state.message = "已开始";
        state.createdAt = Instant.now().getEpochSecond();
        state.updatedAt = state.createdAt;
        jobs.put(jobId, state);
        log.info("启动日程排程 job: userId={}, jobId={}, date={}, mode={}", userId, jobId, request != null ? request.getDate() : null, request != null ? request.getMode() : null);

        DailyPlanCommitRequest commit = new DailyPlanCommitRequest();
        if (request != null) {
            commit.setDate(request.getDate());
            commit.setMode(request.getMode());
        }
        executor.execute(() -> run(jobId, userId, commit));

        DailyPlanJobStartResponse resp = new DailyPlanJobStartResponse();
        resp.setJobId(jobId);
        return resp;
    }

    public DailyPlanJobStatusResponse status(Long userId, String jobId) {
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

    void run(String jobId, Long userId, DailyPlanCommitRequest request) {
        JobState state = jobs.get(jobId);
        if (state == null) {
            return;
        }
        LocalDate date = request != null ? request.getDate() : null;
        update(state, "RUNNING", "PREPARE", 5, "正在准备排程参数" + (date != null ? "（" + date + "）" : ""));
        try {
            DailyPlanCommitResponse resp = scheduleService.commitDailyPlan(userId, request, (stage, progress, message) -> update(state, "RUNNING", stage, progress, message));
            update(state, "DONE", "DONE", 100, "已完成排程并写入日程");
            state.result = resp;
            sendNotification(userId, "SCHEDULE_DONE", "你的智能学习计划已经排好，快去查看吧！");
        } catch (Exception e) {
            update(state, "FAILED", "FAILED", 100, "排程失败");
            state.error = e.getMessage() != null ? e.getMessage() : "服务异常";
            log.warn("日程排程 job 失败: userId={}, jobId={}, error={}", userId, jobId, state.error);
            sendNotification(userId, "SCHEDULE_FAILED", "智能排程失败：" + state.error);
        }
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
        private String status;
        private String stage;
        private Integer progress;
        private String message;
        private String error;
        private DailyPlanCommitResponse result;
        private long createdAt;
        private long updatedAt;

        DailyPlanJobStatusResponse toDto() {
            DailyPlanJobStatusResponse dto = new DailyPlanJobStatusResponse();
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
