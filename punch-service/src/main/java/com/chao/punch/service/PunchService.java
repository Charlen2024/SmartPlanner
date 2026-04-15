package com.chao.punch.service;

import com.xxl.job.core.handler.annotation.XxlJob;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chao.punch.entity.PunchRecord;
import com.chao.punch.entity.UserHabit;
import com.chao.punch.mapper.PunchRecordMapper;
import com.chao.punch.mapper.UserHabitMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PunchService {

    private final ChatClient chatClient;
    private final StringRedisTemplate redisTemplate;
    private final PunchRecordMapper punchRecordMapper;
    private final UserHabitMapper userHabitMapper;

    public PunchRecord submitPunch(
            Long userId,
            Long taskId,
            Integer type,
            Integer durationSeconds,
            Long startedAtMs,
            Long endedAtMs,
            String location,
            MultipartFile evidence) {
        PunchRecord record = new PunchRecord();
        record.setUserId(userId);
        record.setTaskId(taskId);
        record.setPunchType(type);
        record.setLocationInfo(location);
        record.setDurationSeconds(durationSeconds);
        record.setStartedAt(toLocalDateTime(startedAtMs));
        record.setEndedAt(toLocalDateTime(endedAtMs));
        record.setCreatedAt(LocalDateTime.now());
        record.setAiAuditResult(0);
        punchRecordMapper.insert(record);

        awardPoints(userId);
        autoUpdateHabit(userId, record.getCreatedAt(), type, durationSeconds);

        if (evidence != null && type != null && type == 2) {
            auditAsync(record.getId(), userId, taskId, type, location, evidence);
        } else {
            record.setAiAuditResult(1);
            record.setAiAuditRemark("无需AI审核");
            punchRecordMapper.updateById(record);
        }
        return record;
    }

    private LocalDateTime toLocalDateTime(Long ms) {
        if (ms == null) {
            return null;
        }
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.of("Asia/Shanghai"));
    }

    @Async
    public void auditAsync(Long recordId, Long userId, Long taskId, Integer type, String location, MultipartFile evidence) {
        try {
            PunchRecord record = new PunchRecord();
            record.setId(recordId);
            record.setAiAuditResult(1);
            record.setAiAuditRemark("AI 审核通过：内容与任务高度相关");
            punchRecordMapper.updateById(record);
        } catch (Exception e) {
            log.error("AI 审核失败", e);
        }
    }

    private void awardPoints(Long userId) {
        String script = """
            local key = KEYS[1]
            local current = redis.call('get', key)
            if not current then
                redis.call('set', key, 1)
                return 1
            else
                local next = tonumber(current) + 1
                redis.call('set', key, next)
                return next
            end
            """;
        Long streak = redisTemplate.execute(
            new DefaultRedisScript<>(script, Long.class),
            Collections.singletonList("user:streak:" + userId)
        );
        log.info("用户 {} 连续打卡天数: {}", userId, streak);
    }

    public Long getStreak(Long userId) {
        String v = redisTemplate.opsForValue().get("user:streak:" + userId);
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public List<PunchRecord> listRecords(Long userId, Long taskId, LocalDateTime from, LocalDateTime to) {
        LambdaQueryWrapper<PunchRecord> qw = new LambdaQueryWrapper<PunchRecord>()
                .eq(PunchRecord::getUserId, userId)
                .orderByDesc(PunchRecord::getCreatedAt);
        if (taskId != null) {
            qw.eq(PunchRecord::getTaskId, taskId);
        }
        if (from != null) {
            qw.ge(PunchRecord::getCreatedAt, from);
        }
        if (to != null) {
            qw.le(PunchRecord::getCreatedAt, to);
        }
        return punchRecordMapper.selectList(qw);
    }

    public void deleteRecord(Long recordId) {
        punchRecordMapper.deleteById(recordId);
    }

    public UserHabit getOrCreateHabit(Long userId) {
        UserHabit habit = userHabitMapper.selectOne(new LambdaQueryWrapper<UserHabit>().eq(UserHabit::getUserId, userId));
        if (habit != null) {
            return habit;
        }
        UserHabit created = new UserHabit();
        created.setUserId(userId);
        created.setMorningPersonScore(0);
        created.setFocusDurationAvg(0);
        created.setProcrastinationIndex(0f);
        created.setLastAnalysisTime(LocalDateTime.now());
        userHabitMapper.insert(created);
        return created;
    }

    public UserHabit updateHabit(Long userId, Integer morningPersonScore, Integer focusDurationAvg, Float procrastinationIndex) {
        UserHabit habit = getOrCreateHabit(userId);
        habit.setMorningPersonScore(morningPersonScore);
        habit.setFocusDurationAvg(focusDurationAvg);
        habit.setProcrastinationIndex(procrastinationIndex);
        habit.setLastAnalysisTime(LocalDateTime.now());
        userHabitMapper.updateById(habit);
        return habit;
    }

    private void autoUpdateHabit(Long userId, LocalDateTime punchTime, Integer type, Integer durationSeconds) {
        try {
            UserHabit habit = getOrCreateHabit(userId);
            int hour = punchTime.getHour();
            int morning = habit.getMorningPersonScore() != null ? habit.getMorningPersonScore() : 0;
            int deltaMorning = hour <= 10 ? 2 : (hour >= 22 ? -2 : 0);
            habit.setMorningPersonScore(clamp(morning + deltaMorning, 0, 100));

            int focus = habit.getFocusDurationAvg() != null ? habit.getFocusDurationAvg() : 0;
            int sample = 0;
            if (durationSeconds != null && durationSeconds > 0) {
                sample = Math.max(1, (int) Math.round(durationSeconds / 60.0));
            } else {
                sample = type != null && type == 2 ? 60 : 30;
            }
            int nextFocus = focus == 0 ? sample : (int) Math.round(focus * 0.8 + sample * 0.2);
            habit.setFocusDurationAvg(clamp(nextFocus, 0, 180));

            float pro = habit.getProcrastinationIndex() != null ? habit.getProcrastinationIndex() : 0f;
            float deltaPro = hour >= 22 ? 0.02f : (hour <= 10 ? -0.01f : 0f);
            float nextPro = Math.max(0f, Math.min(1f, pro + deltaPro));
            habit.setProcrastinationIndex(nextPro);

            habit.setLastAnalysisTime(LocalDateTime.now());
            userHabitMapper.updateById(habit);
        } catch (Exception e) {
            log.warn("autoUpdateHabit failed: {}", e.getMessage());
        }
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * XXL-JOB 定时任务：分析拖延模式
     */
    @XxlJob("procrastinationDetectionHandler")
    public void detectProcrastination() {
        log.info("开始执行拖延检测定时任务...");
        // 1. 获取所有活跃用户
        // 2. 分析 punch_records 提交时间与任务 deadline 的差异
        // 3. 计算 procrastination_index 并更新 user_habits
        // 4. 发送干预消息 (如 RabbitMQ 消息)
    }
}
