package com.chao.schedule.service;

import com.chao.common.dto.DailyPlanCommitRequest;
import com.chao.common.dto.DailyPlanCommitResponse;
import com.chao.common.dto.DailyPlanJobStartRequest;
import com.chao.common.dto.DailyPlanJobStartResponse;
import com.chao.common.dto.DailyPlanJobStatusResponse;
import com.chao.common.dto.SchedulePreferenceDto;
import lombok.Data;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import com.chao.common.client.ResourceClient;
import com.chao.common.dto.ResourceAdviceJobStartRequest;
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
    private final ResourceClient resourceClient;
    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

    public DailyPlanJobService(ScheduleService scheduleService, @Qualifier("applicationTaskExecutor") Executor executor, RabbitTemplate rabbitTemplate, ResourceClient resourceClient) {
        this.scheduleService = scheduleService;
        this.executor = executor;
        this.rabbitTemplate = rabbitTemplate;
        this.resourceClient = resourceClient;
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
            commit.setGoalId(request.getGoalId());
            commit.setTaskIds(request.getTaskIds());
            commit.setDays(request.getDays());
            commit.setPreference(request.getPreference());
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
        sendScheduleProgress(userId, "SCHEDULE_STARTED", "PREPARE", 5, "开始智能排程", Map.of("date", date != null ? date.toString() : ""));
        try {
            DailyPlanCommitResponse resp = scheduleService.commitDailyPlan(userId, request,
                (stage, progress, message) -> {
                    update(state, "RUNNING", stage, progress, message);
                    sendScheduleProgress(userId, "SCHEDULE_PROGRESS", stage, progress, message, null);
                });
            update(state, "DONE", "DONE", 100, "已完成排程并写入日程");
            state.result = resp;
            triggerResourceAdviceForSchedules(userId, resp);
            // Send SCHEDULE_DONE with scheduling params for the frontend panel
            int scheduleCount = resp != null && resp.getSchedules() != null ? resp.getSchedules().size() : 0;
            SchedulePreferenceDto pref = request != null ? scheduleService.resolvePreference(request.getPreference()) : scheduleService.resolvePreference(null);
            sendScheduleProgress(userId, "SCHEDULE_DONE", "DONE", 100, "排程完成",
                Map.of("taskCount", scheduleCount,
                       "date", date != null ? date.toString() : "",
                       "focusMinutes", pref.getFocusMinutes(),
                       "breakMinutes", pref.getBreakMinutes(),
                       "maxDailyMinutes", pref.getMaxDailyMinutes(),
                       "procrastinationIndex", Math.round(pref.getProcrastinationIndex() * 100) / 100.0));
        } catch (Exception e) {
            update(state, "FAILED", "FAILED", 100, "排程失败");
            state.error = e.getMessage() != null ? e.getMessage() : "服务异常";
            log.warn("日程排程 job 失败: userId={}, jobId={}, error={}", userId, jobId, state.error);
            sendScheduleProgress(userId, "SCHEDULE_FAILED", "FAILED", 100, "排程失败: " + state.error,
                Map.of("error", state.error));
        }
    }

    private void sendScheduleProgress(Long userId, String type, String stage, int progress, String message, Map<String, Object> extra) {
        try {
            NotificationMessage notif = new NotificationMessage();
            notif.setUserId(userId);
            notif.setType(type);
            notif.setContent(message);
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("stage", stage);
            payload.put("progress", progress);
            payload.put("message", message);
            if (extra != null) payload.putAll(extra);
            notif.setPayload(payload);
            rabbitTemplate.convertAndSend(RabbitMqConfig.NOTIFICATION_EXCHANGE, RabbitMqConfig.NOTIFICATION_ROUTING_KEY, notif);
        } catch (Exception e) {
            log.warn("Schedule progress notification FAILED: userId={}, type={}, err={}", userId, type, e.getMessage());
        }
    }

    private void triggerResourceAdviceForSchedules(Long userId, DailyPlanCommitResponse resp) {
        if (resp == null || resp.getSchedules() == null || resp.getSchedules().isEmpty()) {
            log.info("Schedule done, no tasks for advice: userId={}", userId);
            return;
        }
        try {
            Set<String> topics = resp.getSchedules().stream()
                    .filter(s -> s.getTaskTitle() != null && !s.getTaskTitle().isBlank())
                    .map(s -> s.getTaskTitle().trim())
                    .limit(5)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (topics.isEmpty()) return;
            log.info("Schedule done, auto-trigger RAG: userId={}, topics={}", userId, topics);
            for (String topic : topics) {
                try {
                    ResourceAdviceJobStartRequest req = new ResourceAdviceJobStartRequest();
                    req.setTopic(topic);
                    resourceClient.startResourceAdviceJob(userId, req);
                } catch (Exception e) {
                    log.warn("Auto advice trigger FAILED (resource-search not running?): userId={}, topic={}, err={}", userId, topic, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract schedule topics: userId={}, err={}", userId, e.getMessage());
        }
    }

    private void sendNotification(Long userId, String type, String content) {
        try {
            NotificationMessage notif = new NotificationMessage();
            notif.setUserId(userId);
            notif.setType(type);
            notif.setContent(content);
            rabbitTemplate.convertAndSend(RabbitMqConfig.NOTIFICATION_EXCHANGE, RabbitMqConfig.NOTIFICATION_ROUTING_KEY, notif);
        } catch (Exception e) {
            log.warn("Notification FAILED (RabbitMQ not running?): userId={}, type={}, err={}", userId, type, e.getMessage());
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
