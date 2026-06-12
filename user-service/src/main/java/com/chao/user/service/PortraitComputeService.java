package com.chao.user.service;

import com.chao.common.client.AgentPortraitClient;
import com.chao.common.client.PunchClient;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.*;
import com.chao.user.dto.UserInsightDto;
import com.chao.user.dto.UserPortraitDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortraitComputeService {

    private final PunchClient punchClient;
    private final ScheduleClient scheduleClient;
    private final AgentPortraitClient agentPortraitClient;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<RedissonClient> redissonProvider;

    private static final String PORTRAIT_CACHE_KEY_PREFIX = "sp:portrait:";
    private static final int PORTRAIT_CACHE_TTL_DAYS = 7;

    public UserPortraitDto recompute(Long userId) {
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(7);
        String fStr = from.toString();
        String tStr = to.toString();

        List<PunchRecordDto> records = safeList(punchClient.listRecords(userId, null, fStr, tStr));
        List<TaskScheduleDto> schedules = safeList(scheduleClient.listTaskSchedules(userId, fStr, tStr));
        UserInsightDto insights = computeInsights(userId, from, to, records, schedules);
        int streak = insights.getStreak() != null ? insights.getStreak() : 0;

        int morningScore = computeMorningScore(records, schedules);
        int focusAvg = computeFocusAvgMinutes(records, schedules);
        float proIndex = computeProcrastinationIndex(insights, records, schedules);

        AiPortraitResult ai = null;
        try {
            PortraitRecomputeRequest req = new PortraitRecomputeRequest();
            req.setUserId(userId);
            req.setPunchRecords(records);
            req.setSchedules(schedules);
            req.setStreak(streak);
            req.setOnTimeRate(insights.getOnTimeRate());
            req.setAvgDelayMinutes(insights.getAvgDelayMinutes());
            req.setCompletionRate(insights.getCompletionRate());
            req.setMatchedPunchCount(insights.getMatchedPunchCount());
            req.setMorningPersonScore(morningScore);
            req.setFocusDurationAvg(focusAvg);
            req.setProcrastinationIndex((double) proIndex);
            Result<AiPortraitResult> res = agentPortraitClient.recomputePortrait(req);
            ai = res != null ? res.getData() : null;
        } catch (Exception e) {
            log.warn("AI portrait recompute failed for userId={}, falling back to local", userId, e);
        }

        int finalMorningScore = ai != null && ai.getMorningPersonScore() != null
                ? ai.getMorningPersonScore() : morningScore;
        int finalFocusAvg = ai != null && ai.getFocusDurationAvg() != null
                ? ai.getFocusDurationAvg() : focusAvg;
        float finalProIndex = ai != null && ai.getProcrastinationIndex() != null
                ? ai.getProcrastinationIndex().floatValue() : proIndex;

        UserHabitDto habits = null;
        try {
            habits = punchClient.updateHabits(userId, finalMorningScore, finalFocusAvg, finalProIndex).getData();
        } catch (Exception e) {
            log.error("Failed to persist habits for userId={}", userId, e);
        }

        UserPortraitDto dto = new UserPortraitDto();
        dto.setHabits(habits);
        dto.setInsights(insights);
        dto.setRecommendation(ai != null && ai.getRecommendation() != null
                ? ai.getRecommendation() : recommend(insights, habits));
        dto.setTips(ai != null && ai.getTips() != null && !ai.getTips().isEmpty()
                ? ai.getTips() : insights.getTips());
        buildComputation(dto, records, schedules);
        cachePortrait(userId, dto);
        return dto;
    }

    public UserPortraitDto load(Long userId) {
        UserPortraitDto cached = getCachedPortrait(userId);
        if (cached != null) {
            LocalDateTime to = LocalDateTime.now();
            LocalDateTime from = to.minusDays(7);
            UserInsightDto insights = computeInsightsFromFeign(userId, from, to);
            cached.setInsights(insights);
            cached.setRecommendation(recommend(insights, cached.getHabits()));
            buildComputation(cached, null, null);
            return cached;
        }
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(7);

        UserPortraitDto dto = new UserPortraitDto();
        try {
            dto.setHabits(punchClient.getHabits(userId).getData());
        } catch (Exception e) {
            log.warn("Failed to load habits for userId={}", userId, e);
        }
        UserInsightDto insights = computeInsightsFromFeign(userId, from, to);
        dto.setInsights(insights);
        dto.setRecommendation(recommend(insights, dto.getHabits()));
        dto.setTips(insights.getTips());
        buildComputation(dto, null, null);
        return dto;
    }

    // ---- insight computation ----

    private UserInsightDto computeInsightsFromFeign(Long userId, LocalDateTime from, LocalDateTime to) {
        String fStr = from != null ? from.toString() : null;
        String tStr = to != null ? to.toString() : null;
        List<PunchRecordDto> records = safeList(punchClient.listRecords(userId, null, fStr, tStr));
        List<TaskScheduleDto> schedules = safeList(scheduleClient.listTaskSchedules(userId, fStr, tStr));
        return computeInsights(userId, from, to, records, schedules);
    }

    private UserInsightDto computeInsights(Long userId, LocalDateTime from, LocalDateTime to,
                                           List<PunchRecordDto> records, List<TaskScheduleDto> schedules) {
        UserInsightDto dto = new UserInsightDto();
        try {
            Long streak = punchClient.getStreak(userId).getData();
            dto.setStreak(streak != null ? streak.intValue() : 0);
        } catch (Exception e) {
            dto.setStreak(0);
        }
        dto.setTips(new ArrayList<>());

        if (records == null || records.isEmpty() || schedules == null || schedules.isEmpty()) {
            dto.setOnTimeRate(0.0);
            dto.setAvgDelayMinutes(0.0);
            dto.setMatchedPunchCount(0);
            dto.setOnTimeCount(0);
            dto.setLateCount(0);
            dto.setTotalDelayMinutes(0L);
            dto.setTotalSchedules(schedules != null ? schedules.size() : 0);
            dto.setDoneCount(0);
            dto.setCompletionRate(0.0);
            dto.getTips().add("先完成一次目标拆解与智能排程，系统才能根据行为生成更准确建议");
            dto.getTips().add("建议每天固定一个时间段学习并打卡，连续 7 天后画像会更稳定");
            return dto;
        }

        Map<Long, List<TaskScheduleDto>> scheduleByTask = new HashMap<>();
        for (TaskScheduleDto s : schedules) {
            if (s != null && s.getTaskId() != null && s.getStartTime() != null) {
                scheduleByTask.computeIfAbsent(s.getTaskId(), k -> new ArrayList<>()).add(s);
            }
        }

        int matched = 0;
        int onTime = 0;
        int lateCount = 0;
        long delaySum = 0;

        for (PunchRecordDto r : records) {
            if (r == null || r.getTaskId() == null) continue;
            LocalDateTime punchTime = punchStartTime(r);
            if (punchTime == null) continue;
            List<TaskScheduleDto> ss = scheduleByTask.get(r.getTaskId());
            if (ss == null || ss.isEmpty()) continue;
            TaskScheduleDto nearest = ss.stream()
                    .min(Comparator.comparing(s -> Math.abs(ChronoUnit.MINUTES.between(s.getStartTime(), punchTime))))
                    .orElse(null);
            if (nearest == null || nearest.getStartTime() == null) continue;
            long delay = ChronoUnit.MINUTES.between(nearest.getStartTime(), punchTime);
            if (Math.abs(delay) > 180) continue;
            matched++;
            if (delay > 0) { delaySum += delay; lateCount++; }
            if (Math.abs(delay) <= 10) onTime++;
        }

        dto.setOnTimeRate(matched == 0 ? 0.0 : onTime * 1.0 / matched);
        dto.setAvgDelayMinutes(lateCount == 0 ? 0.0 : delaySum * 1.0 / lateCount);
        dto.setMatchedPunchCount(matched);
        dto.setOnTimeCount(onTime);
        dto.setLateCount(lateCount);
        dto.setTotalDelayMinutes(delaySum);
        dto.setTotalSchedules(schedules.size());
        long doneSchedules = schedules.stream().filter(s -> s != null && s.getStatus() != null && s.getStatus() == 1).count();
        dto.setDoneCount((int) doneSchedules);
        dto.setCompletionRate(schedules.isEmpty() ? 0.0 : doneSchedules * 1.0 / schedules.size());

        if (matched < 3) {
            dto.getTips().add(String.format("匹配打卡次数 %d（近 7 天），完成率 %.0f%%，准时率 %.0f%%，平均迟到 %.0f 分钟",
                    matched, dto.getCompletionRate() * 100, dto.getOnTimeRate() * 100, dto.getAvgDelayMinutes()));
            dto.getTips().add("匹配数据较少：建议用计时打卡完成 3 次以上后再看画像与建议");
            return dto;
        }
        dto.getTips().add(String.format("完成率 %.0f%%，准时率 %.0f%%，平均迟到 %.0f 分钟（仅统计迟到）",
                dto.getCompletionRate() * 100, dto.getOnTimeRate() * 100, dto.getAvgDelayMinutes()));
        if (dto.getOnTimeRate() < 0.5) {
            dto.getTips().add("你的打卡更偏「临时起意」，建议把学习安排在固定时间段，提升准时率");
        } else {
            dto.getTips().add("准时率不错，保持固定节奏更容易形成稳定习惯");
        }
        if (dto.getAvgDelayMinutes() > 30 && lateCount >= 2) {
            dto.getTips().add("平均延迟较大，建议把任务拆得更小（30-60 分钟）并减少一次性任务量");
        }
        if (dto.getStreak() != null && dto.getStreak() < 3) {
            dto.getTips().add("先把连续打卡目标定为 3 天，完成后再提升到 7 天");
        }
        return dto;
    }

    // ---- local metric computation ----

    private int computeMorningScore(List<PunchRecordDto> records, List<TaskScheduleDto> schedules) {
        double punchRatio = 0.0;
        if (records != null && !records.isEmpty()) {
            long morning = records.stream().filter(r -> {
                LocalDateTime t = punchStartTime(r);
                return t != null && t.getHour() <= 10;
            }).count();
            punchRatio = morning * 1.0 / records.size();
        }
        double scheduleRatio = 0.0;
        if (schedules != null && !schedules.isEmpty()) {
            long morning = schedules.stream().filter(s -> s != null && s.getStartTime() != null && s.getStartTime().getHour() <= 10).count();
            scheduleRatio = morning * 1.0 / schedules.size();
        }
        int score = (int) Math.round((punchRatio * 0.6 + scheduleRatio * 0.4) * 100);
        return Math.max(0, Math.min(100, score));
    }

    private int computeFocusAvgMinutes(List<PunchRecordDto> records, List<TaskScheduleDto> schedules) {
        if (records != null && !records.isEmpty()) {
            long sum = 0;
            int cnt = 0;
            for (PunchRecordDto r : records) {
                if (r == null || r.getDurationSeconds() == null || r.getDurationSeconds() <= 0) continue;
                sum += Math.max(1, Math.round(r.getDurationSeconds() / 60.0));
                cnt++;
            }
            if (cnt > 0) {
                int avg = (int) Math.round(sum * 1.0 / cnt);
                return Math.max(0, Math.min(180, avg));
            }
        }
        if (schedules == null || schedules.isEmpty()) return 0;
        long sum = 0;
        int cnt = 0;
        for (TaskScheduleDto s : schedules) {
            if (s == null || s.getStartTime() == null || s.getEndTime() == null) continue;
            long mins = ChronoUnit.MINUTES.between(s.getStartTime(), s.getEndTime());
            if (mins <= 0) continue;
            if (s.getStatus() != null && s.getStatus() == 1) { sum += mins; cnt++; }
        }
        if (cnt == 0) {
            for (TaskScheduleDto s : schedules) {
                if (s == null || s.getStartTime() == null || s.getEndTime() == null) continue;
                long mins = ChronoUnit.MINUTES.between(s.getStartTime(), s.getEndTime());
                if (mins > 0) { sum += mins; cnt++; }
            }
        }
        int avg = cnt == 0 ? 0 : (int) Math.round(sum * 1.0 / cnt);
        return Math.max(0, Math.min(180, avg));
    }

    private float computeProcrastinationIndex(UserInsightDto insights, List<PunchRecordDto> records, List<TaskScheduleDto> schedules) {
        if (insights == null) return 0f;
        double delay = insights.getAvgDelayMinutes() != null ? insights.getAvgDelayMinutes() : 0.0;
        double onTime = insights.getOnTimeRate() != null ? insights.getOnTimeRate() : 0.0;
        double delayScore = Math.max(0.0, Math.min(1.0, delay / 180.0));
        double completionRate = 0.0;
        if (schedules != null && !schedules.isEmpty()) {
            long done = schedules.stream().filter(s -> s != null && s.getStatus() != null && s.getStatus() == 1).count();
            completionRate = done * 1.0 / schedules.size();
        }
        Integer matched = insights.getMatchedPunchCount();
        if (matched == null || matched < 3) {
            double pro = 0.3 + (1.0 - completionRate) * 0.7;
            pro = Math.min(0.85, pro);
            return (float) Math.max(0.0, Math.min(1.0, pro));
        }
        double pro = delayScore * 0.45 + (1.0 - onTime) * 0.35 + (1.0 - completionRate) * 0.20;
        return (float) Math.max(0.0, Math.min(1.0, pro));
    }

    private SchedulePreferenceDto recommend(UserInsightDto insights, UserHabitDto habits) {
        // 1. Focus: continuous mapping from avg focus, then procrastination penalty
        int focusAvg = habits != null && habits.getFocusDurationAvg() != null ? habits.getFocusDurationAvg() : 45;
        int focusBase = (int) Math.round(focusAvg * 0.8 / 5.0) * 5;
        focusBase = Math.max(25, Math.min(90, focusBase));

        double proIndex = habits != null && habits.getProcrastinationIndex() != null ? habits.getProcrastinationIndex() : 0.0;
        int focusPenalty = 0;
        if (proIndex > 0.6) focusPenalty = 10;
        else if (proIndex > 0.4) focusPenalty = 5;
        int focus = Math.max(25, focusBase - focusPenalty);

        // 2. Break: ~25% of focus, rounded to 5
        int breakMin = Math.max(5, Math.min(25, (int) Math.round(focus * 0.25 / 5.0) * 5));

        // 3. Max daily: completion tier → procrastination penalty → streak safety net
        double completionRate = insights != null && insights.getCompletionRate() != null ? insights.getCompletionRate() : 0.0;
        int maxDailyBase;
        if (completionRate < 0.3) maxDailyBase = 120;
        else if (completionRate < 0.6) maxDailyBase = 180;
        else maxDailyBase = 240;

        int procPenalty = 0;
        if (proIndex > 0.7) procPenalty = 60;
        else if (proIndex > 0.5) procPenalty = 30;
        int maxDaily = Math.max(120, maxDailyBase - procPenalty);

        int streak = insights != null && insights.getStreak() != null ? insights.getStreak() : 0;
        if (streak < 2) {
            maxDaily = Math.min(maxDaily, 150);
        }

        SchedulePreferenceDto dto = new SchedulePreferenceDto();
        dto.setFocusMinutes(focus);
        dto.setBreakMinutes(breakMin);
        dto.setMaxDailyMinutes(maxDaily);
        return dto;
    }

    // ---- computation details ----

    private void buildComputation(UserPortraitDto dto, List<PunchRecordDto> records, List<TaskScheduleDto> schedules) {
        Map<String, Object> c = dto.getComputation();
        UserInsightDto ins = dto.getInsights();
        UserHabitDto hab = dto.getHabits();

        int matched = ins.getMatchedPunchCount() != null ? ins.getMatchedPunchCount() : 0;
        int onTime = ins.getOnTimeCount() != null ? ins.getOnTimeCount() : 0;
        c.put("onTimeRate", Map.of(
                "label", "准时率", "formula", "准时次数 ÷ 匹配次数",
                "inputs", Map.of("onTimeCount", onTime, "matchedCount", matched),
                "result", Math.round((ins.getOnTimeRate() != null ? ins.getOnTimeRate() : 0) * 100) + "%"));

        int late = ins.getLateCount() != null ? ins.getLateCount() : 0;
        long delaySum = ins.getTotalDelayMinutes() != null ? ins.getTotalDelayMinutes() : 0L;
        c.put("avgDelay", Map.of(
                "label", "平均延迟", "formula", "总延迟分钟 ÷ 迟到次数（仅统计迟到）",
                "inputs", Map.of("totalDelayMinutes", delaySum, "lateCount", late),
                "result", Math.round(ins.getAvgDelayMinutes() != null ? ins.getAvgDelayMinutes() : 0) + " min"));

        int totalSch = ins.getTotalSchedules() != null ? ins.getTotalSchedules() : 0;
        int done = ins.getDoneCount() != null ? ins.getDoneCount() : 0;
        c.put("completionRate", Map.of(
                "label", "完成率", "formula", "已完成排程数 ÷ 总排程数",
                "inputs", Map.of("doneCount", done, "totalSchedules", totalSch),
                "result", Math.round((ins.getCompletionRate() != null ? ins.getCompletionRate() : 0) * 100) + "%"));

        c.put("streak", Map.of(
                "label", "连续打卡", "formula", "最近连续打卡天数",
                "inputs", Map.of("streak", ins.getStreak() != null ? ins.getStreak() : 0),
                "result", String.valueOf(ins.getStreak() != null ? ins.getStreak() : 0) + " 天"));

        if (records != null && schedules != null && !records.isEmpty() && !schedules.isEmpty()) {
            long morningPunch = records.stream().filter(r -> { LocalDateTime t = punchStartTime(r); return t != null && t.getHour() <= 10; }).count();
            long morningSch = schedules.stream().filter(s -> s != null && s.getStartTime() != null && s.getStartTime().getHour() <= 10).count();
            double punchRatio = morningPunch * 1.0 / records.size();
            double schRatio = morningSch * 1.0 / schedules.size();
            int localMorningScore = (int) Math.round((punchRatio * 0.6 + schRatio * 0.4) * 100);
            localMorningScore = Math.max(0, Math.min(100, localMorningScore));
            int habMorning = hab != null && hab.getMorningPersonScore() != null ? hab.getMorningPersonScore() : 0;
            boolean aiRefinedMorning = habMorning != localMorningScore;
            c.put("morningScore", Map.of(
                    "label", "晨型倾向",
                    "formula", "晨间打卡÷总打卡数 × 60 + 晨间排程÷总排程数 × 40" + (aiRefinedMorning ? " → AI微调" : ""),
                    "inputs", Map.of("morningPunchCount", morningPunch, "totalPunchRecords", records.size(),
                            "morningScheduleCount", morningSch, "totalSchedules", schedules.size(),
                            "localResult", localMorningScore),
                    "result", String.valueOf(habMorning)));
        } else {
            int habMorning = hab != null && hab.getMorningPersonScore() != null ? hab.getMorningPersonScore() : 0;
            c.put("morningScore", Map.of(
                    "label", "晨型倾向", "formula", "晨间打卡÷总打卡数 × 60 + 晨间排程÷总排程数 × 40",
                    "inputs", Map.of("localResult", habMorning),
                    "result", String.valueOf(habMorning)));
        }

        if (records != null && !records.isEmpty()) {
            long durSum = 0; int durCnt = 0;
            for (PunchRecordDto r : records) {
                if (r == null || r.getDurationSeconds() == null || r.getDurationSeconds() <= 0) continue;
                durSum += Math.max(1, Math.round(r.getDurationSeconds() / 60.0)); durCnt++;
            }
            int localFocus = durCnt > 0 ? (int) Math.round(durSum * 1.0 / durCnt) : 0;
            localFocus = Math.max(0, Math.min(180, localFocus));
            int habFocus = hab != null && hab.getFocusDurationAvg() != null ? hab.getFocusDurationAvg() : 0;
            boolean aiRefinedFocus = habFocus != localFocus;
            c.put("focusAvg", Map.of(
                    "label", "平均专注时长",
                    "formula", "打卡总时长(分钟) ÷ 打卡次数" + (aiRefinedFocus ? " → AI微调" : ""),
                    "inputs", Map.of("totalDurationMinutes", durSum, "punchCount", durCnt,
                            "localResult", localFocus),
                    "result", habFocus + " min"));
        } else {
            int habFocus = hab != null && hab.getFocusDurationAvg() != null ? hab.getFocusDurationAvg() : 0;
            c.put("focusAvg", Map.of(
                    "label", "平均专注时长", "formula", "打卡总时长(分钟) ÷ 打卡次数",
                    "inputs", Map.of("localResult", habFocus),
                    "result", habFocus + " min"));
        }

        double delay = ins.getAvgDelayMinutes() != null ? ins.getAvgDelayMinutes() : 0;
        double delayScore = Math.max(0, Math.min(1, delay / 180));
        double onTimeRate = ins.getOnTimeRate() != null ? ins.getOnTimeRate() : 0;
        double completionRate = ins.getCompletionRate() != null ? ins.getCompletionRate() : 0;
        double localProc;
        if (matched < 3) {
            localProc = Math.min(0.85, 0.3 + (1.0 - completionRate) * 0.7);
        } else {
            localProc = delayScore * 0.45 + (1.0 - onTimeRate) * 0.35 + (1.0 - completionRate) * 0.20;
        }
        localProc = Math.max(0.0, Math.min(1.0, localProc));
        double habProc = hab != null && hab.getProcrastinationIndex() != null ? hab.getProcrastinationIndex() : 0.0;
        int localProcPct = (int) Math.round(localProc * 100);
        int habProcPct = (int) Math.round(habProc * 100);
        boolean aiRefinedProc = localProcPct != habProcPct;
        c.put("procrastination", Map.of(
                "label", "拖延指数",
                "formula", (matched >= 3
                        ? "(平均延迟÷180) × 0.45 + (1−准时率) × 0.35 + (1−完成率) × 0.20"
                        : "样本不足(<3): 0.3 + (1−完成率) × 0.7")
                        + (aiRefinedProc ? " → AI微调" : ""),
                "inputs", Map.of("delayScore", Math.round(delayScore * 100) / 100.0,
                        "onTimeRate", Math.round(onTimeRate * 100) / 100.0,
                        "completionRate", Math.round(completionRate * 100) / 100.0, "matchedCount", matched,
                        "localResult", localProcPct + "%"),
                "result", habProcPct + "%"));

        SchedulePreferenceDto rec = dto.getRecommendation();
        // Compute local recommendation intermediates for the decision-flow display
        int focusAvg = hab != null && hab.getFocusDurationAvg() != null ? hab.getFocusDurationAvg() : 45;
        int focusBase = (int) Math.round(focusAvg * 0.8 / 5.0) * 5;
        focusBase = Math.max(25, Math.min(90, focusBase));
        double procIdx = hab != null && hab.getProcrastinationIndex() != null ? hab.getProcrastinationIndex() : 0.0;
        int focusPenalty = 0;
        if (procIdx > 0.6) focusPenalty = 10;
        else if (procIdx > 0.4) focusPenalty = 5;
        int localFocus = Math.max(25, focusBase - focusPenalty);
        int localBreak = Math.max(5, Math.min(25, (int) Math.round(localFocus * 0.25 / 5.0) * 5));
        int localMaxBase;
        if (completionRate < 0.3) localMaxBase = 120;
        else if (completionRate < 0.6) localMaxBase = 180;
        else localMaxBase = 240;
        int procPenalty = 0;
        if (procIdx > 0.7) procPenalty = 60;
        else if (procIdx > 0.5) procPenalty = 30;
        int localMaxDaily = Math.max(120, localMaxBase - procPenalty);
        int streak = ins.getStreak() != null ? ins.getStreak() : 0;
        boolean streakCapped = streak < 2;
        if (streakCapped) localMaxDaily = Math.min(localMaxDaily, 150);

        boolean aiRefinedRec = rec != null
                && (rec.getFocusMinutes() != localFocus
                    || rec.getBreakMinutes() != localBreak
                    || rec.getMaxDailyMinutes() != localMaxDaily);

        Map<String, Object> recInputs = new LinkedHashMap<>();
        recInputs.put("focusAvgInput", focusAvg);
        recInputs.put("procrastinationInput", Math.round(procIdx * 100) / 100.0);
        recInputs.put("completionRateInput", Math.round(completionRate * 100) / 100.0);
        recInputs.put("streakInput", streak);
        recInputs.put("focusBase", focusBase);
        recInputs.put("focusPenalty", focusPenalty);
        recInputs.put("completionTier", localMaxBase);
        recInputs.put("procPenalty", procPenalty);
        recInputs.put("streakCapped", streakCapped);

        c.put("recommendation", Map.of(
                "label", "排程推荐",
                "formula", "专注=clamp(round(avg×0.8÷5)×5,25,90)−拖延罚分; 休息=clamp(round(专注×0.25÷5)×5,5,25); 上限=完成率分档−拖延罚分,streak<2封顶150"
                        + (aiRefinedRec ? " → AI微调" : ""),
                "inputs", recInputs,
                "result", rec != null
                        ? "专注" + rec.getFocusMinutes() + "min / 休息" + rec.getBreakMinutes() + "min / 上限" + rec.getMaxDailyMinutes() + "min"
                        : "-"));
    }

    // ---- helpers ----

    private static <T> List<T> safeList(Result<List<T>> res) {
        if (res == null || res.getCode() != 200) return List.of();
        List<T> data = res.getData();
        return data != null ? data : List.of();
    }

    private static LocalDateTime punchStartTime(PunchRecordDto r) {
        if (r == null) return null;
        if (r.getStartedAt() != null) return r.getStartedAt();
        if (r.getEndedAt() != null && r.getDurationSeconds() != null && r.getDurationSeconds() > 0)
            return r.getEndedAt().minusSeconds(r.getDurationSeconds());
        if (r.getCreatedAt() != null && r.getDurationSeconds() != null && r.getDurationSeconds() > 0)
            return r.getCreatedAt().minusSeconds(r.getDurationSeconds());
        return r.getCreatedAt();
    }

    // ---- Redis cache ----

    private void cachePortrait(Long userId, UserPortraitDto dto) {
        try {
            RedissonClient r = redissonProvider.getIfAvailable();
            if (r == null) return;
            String json = objectMapper.writeValueAsString(dto);
            r.getBucket(PORTRAIT_CACHE_KEY_PREFIX + userId).set(json, PORTRAIT_CACHE_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.debug("Portrait cache write failed for userId={}: {}", userId, e.toString());
        }
    }

    private UserPortraitDto getCachedPortrait(Long userId) {
        try {
            RedissonClient r = redissonProvider.getIfAvailable();
            if (r == null) return null;
            String json = String.valueOf(r.getBucket(PORTRAIT_CACHE_KEY_PREFIX + userId).get());
            if (json == null || "null".equals(json) || json.isBlank()) return null;
            return objectMapper.readValue(json, UserPortraitDto.class);
        } catch (Exception e) {
            log.debug("Portrait cache read failed for userId={}: {}", userId, e.toString());
            return null;
        }
    }
}
