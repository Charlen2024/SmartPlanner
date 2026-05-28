package com.chao.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.client.GoalClient;
import com.chao.common.dto.FreeSlotDto;
import com.chao.common.dto.GoalTaskDto;
import com.chao.common.dto.SchedulePreferenceDto;
import com.chao.common.dto.TaskScheduleDto;
import com.chao.schedule.entity.PlanCandidate;
import com.chao.schedule.mapper.PlanCandidateMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlanCandidateWorker {
    private final OpenAiCompatClient openAiCompatClient;
    private final ObjectMapper objectMapper;
    private final PlanCandidateMapper planCandidateMapper;
    private final GoalClient goalClient;

    private static final long CANDIDATE_AI_TIMEOUT_SECONDS = 600;
    private static final int CANDIDATE_STATUS_READY = 0;
    private static final int MIN_SLOT_MINUTES = 10;
    private static final int SESSION_MINUTES = 45;
    private static final int BREAK_MINUTES = 10;
    private static final int MAX_STUDY_MINUTES_PER_DAY = 240;

    private static final String PLAN_SYSTEM_PROMPT = """
        你是一个学习计划排程助手。你将收到：
        1) 用户某天的空闲时间段列表（freeSlots）
        2) 用户待办学习任务列表（tasks）
        3) 用户画像（userProfile）：包含 procrastinationIndex（拖延指数 0-1）、
           focusMinutes（建议单次专注时长）、breakMinutes（休息间隔）、maxDailyMinutes（当日学习上限）
        你的目标是给出"候选排程建议"（candidateSchedules），把任务分散安排到空闲时间中。

        输出必须是严格 JSON（不要 Markdown、不要额外文字），格式：
        {
          "note": "简短说明",
          "candidateSchedules": [{"taskId":123,"startTime":"YYYY-MM-DDTHH:mm:ss","endTime":"YYYY-MM-DDTHH:mm:ss"}]
        }

        排程策略（根据用户画像动态调整）：
        - 单次学习 = focusMinutes 分钟 + breakMinutes 分钟休息，当日总时长 ≤ maxDailyMinutes
        - procrastinationIndex > 0.6：用户容易拖延，安排应保守——减少任务数、多留缓冲、优先安排短任务
        - procrastinationIndex < 0.3：用户自律性强，可适度紧凑安排
        - 严禁与课表冲突：所有安排必须落在 freeSlots 内
        """;

    @Async
    public void generate(Long candidateId, Long userId, LocalDate date, List<FreeSlotDto> freeSlots, List<GoalTaskDto> tasks, SchedulePreferenceDto pref) {
        SchedulePreferenceDto p = pref;
        if (p == null) {
            p = new SchedulePreferenceDto();
        }
        if (p.getFocusMinutes() == null || p.getFocusMinutes() <= 0) p.setFocusMinutes(SESSION_MINUTES);
        if (p.getBreakMinutes() == null || p.getBreakMinutes() <= 0) p.setBreakMinutes(BREAK_MINUTES);
        if (p.getMaxDailyMinutes() == null || p.getMaxDailyMinutes() <= 0) p.setMaxDailyMinutes(MAX_STUDY_MINUTES_PER_DAY);
        if (p.getProcrastinationIndex() == null) p.setProcrastinationIndex(0.3f);
        try {
            CandidateAiResponse ai;
            try {
                String prompt = buildPlanPrompt(date, freeSlots, tasks, p);
                String aiJson = CompletableFuture
                        .supplyAsync(() -> openAiCompatClient.complete(prompt))
                        .orTimeout(CANDIDATE_AI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .join();
                ai = parseCandidateAiResponse(aiJson);
            } catch (Exception e) {
                ai = new CandidateAiResponse();
                ai.note = "已生成候选排程";
                ai.candidateSchedules = ruleBasedCandidateSchedules(freeSlots, tasks, p);
            }

            Map<Long, String> taskTitleMap = tasks.stream()
                    .filter(t -> t != null && t.getId() != null)
                    .collect(Collectors.toMap(GoalTaskDto::getId, GoalTaskDto::getTitle, (a, b) -> a));

            List<TaskScheduleDto> candidateSchedules = normalizeSchedules(ai.candidateSchedules);

            if (candidateSchedules.isEmpty()) {
                candidateSchedules = ruleBasedCandidateSchedules(freeSlots, tasks, p);
            }
            if (candidateSchedules.isEmpty()) {
                ai.note = "当前提供的空闲时间段太短，无法安排任何学习任务。建议增加更多可用时间（建议单段≥" + MIN_SLOT_MINUTES + "分钟）。";
            }

            int freeCount = freeSlots != null ? freeSlots.size() : 0;
            long freeMinutes = 0;
            if (freeSlots != null) {
                for (FreeSlotDto f : freeSlots) {
                    if (f != null && f.getStart() != null && f.getEnd() != null && f.getEnd().isAfter(f.getStart())) {
                        freeMinutes += java.time.Duration.between(f.getStart(), f.getEnd()).toMinutes();
                    }
                }
            }
            String baseNote = (ai.note == null || ai.note.isBlank()) ? "已生成候选排程" : ai.note;
            ai.note = baseNote + "（当日空闲 " + freeCount + " 段 / " + freeMinutes + " 分钟，学习上限 " + MAX_STUDY_MINUTES_PER_DAY + " 分钟）";

            for (TaskScheduleDto s : candidateSchedules) {
                if (s != null && s.getTaskId() != null && (s.getTaskTitle() == null || s.getTaskTitle().isBlank())) {
                    s.setTaskTitle(taskTitleMap.get(s.getTaskId()));
                }
            }

            PlanCandidate upd = new PlanCandidate();
            upd.setId(candidateId);
            upd.setStatus(CANDIDATE_STATUS_READY);
            upd.setNote(ai.note);
            upd.setSchedulesJson(writeJson(candidateSchedules));
            upd.setSuggestedFreeSlotsJson(null);
            upd.setSuggestedSchedulesJson(null);
            planCandidateMapper.updateById(upd);
        } catch (Exception e) {
            log.error("候选排程后台生成失败", e);
            PlanCandidate upd = new PlanCandidate();
            upd.setId(candidateId);
            upd.setStatus(CANDIDATE_STATUS_READY);
            upd.setNote("候选排程生成失败，请重试");
            upd.setSchedulesJson("[]");
            planCandidateMapper.updateById(upd);
        }
    }

    public List<TaskScheduleDto> generateRuleBasedSchedules(List<FreeSlotDto> freeSlots, List<GoalTaskDto> tasks, SchedulePreferenceDto pref) {
        return ruleBasedCandidateSchedules(freeSlots, tasks, pref);
    }

    private String buildPlanPrompt(LocalDate date, List<FreeSlotDto> freeSlots, List<GoalTaskDto> tasks, SchedulePreferenceDto p) {
        String freeJson = writeJson(freeSlots);
        List<Map<String, Object>> simpleTasks = tasks.stream().map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.getId());
            m.put("title", t.getTitle());
            m.put("estimatedMinutes", t.getEstimatedMinutes());
            m.put("priority", t.getPriority());
            return m;
        }).collect(Collectors.toList());
        String taskJson = writeJson(simpleTasks);
        String profileJson = writeJson(Map.of(
            "focusMinutes", p.getFocusMinutes(),
            "breakMinutes", p.getBreakMinutes(),
            "maxDailyMinutes", p.getMaxDailyMinutes(),
            "procrastinationIndex", p.getProcrastinationIndex()
        ));
        return PLAN_SYSTEM_PROMPT + "\nplanDate: " + date + "\nuserProfile: " + profileJson + "\nfreeSlots: " + freeJson + "\ntasks: " + taskJson;
    }

    private CandidateAiResponse parseCandidateAiResponse(String aiJson) {
        try {
            String sanitized = sanitizeJsonObject(aiJson);
            return objectMapper.readValue(sanitized, CandidateAiResponse.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("候选计划解析失败");
        }
    }

    private String sanitizeJsonObject(String text) {
        if (text == null) {
            return "{}";
        }
        String s = text.trim();
        if (s.startsWith("```")) {
            int firstBrace = s.indexOf('{');
            int lastBrace = s.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                s = s.substring(firstBrace, lastBrace + 1).trim();
            }
        }
        int firstBrace = s.indexOf('{');
        int lastBrace = s.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return s.substring(firstBrace, lastBrace + 1).trim();
        }
        return s;
    }

    private List<FreeSlotDto> normalizeSlots(LocalDate date, List<FreeSlotDto> slots) {
        if (slots == null || slots.isEmpty()) {
            return List.of();
        }
        List<FreeSlotDto> list = slots.stream()
                .filter(s -> s != null && s.getStart() != null && s.getEnd() != null && s.getEnd().isAfter(s.getStart()))
                .filter(s -> s.getStart().toLocalDate().equals(date))
                .sorted((a, b) -> a.getStart().compareTo(b.getStart()))
                .collect(Collectors.toList());
        List<FreeSlotDto> out = new ArrayList<>();
        for (FreeSlotDto s : list) {
            FreeSlotDto last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last == null) {
                out.add(s);
                continue;
            }
            if (!s.getStart().isAfter(last.getEnd())) {
                if (s.getEnd().isAfter(last.getEnd())) {
                    last.setEnd(s.getEnd());
                }
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private List<TaskScheduleDto> normalizeSchedules(List<TaskScheduleDto> schedules) {
        if (schedules == null) {
            return List.of();
        }
        return schedules.stream()
                .filter(s -> s != null && s.getTaskId() != null && s.getStartTime() != null && s.getEndTime() != null)
                .filter(s -> s.getEndTime().isAfter(s.getStartTime()))
                .sorted((a, b) -> a.getStartTime().compareTo(b.getStartTime()))
                .collect(Collectors.toList());
    }

    private List<TaskScheduleDto> ruleBasedCandidateSchedules(List<FreeSlotDto> freeSlots, List<GoalTaskDto> tasks, SchedulePreferenceDto pref) {
        if (freeSlots == null || freeSlots.isEmpty() || tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        SchedulePreferenceDto p = pref != null ? pref : new SchedulePreferenceDto();
        if (p.getFocusMinutes() == null || p.getFocusMinutes() <= 0) p.setFocusMinutes(SESSION_MINUTES);
        if (p.getBreakMinutes() == null || p.getBreakMinutes() <= 0) p.setBreakMinutes(BREAK_MINUTES);
        if (p.getMaxDailyMinutes() == null || p.getMaxDailyMinutes() <= 0) p.setMaxDailyMinutes(MAX_STUDY_MINUTES_PER_DAY);
        if (p.getProcrastinationIndex() == null) p.setProcrastinationIndex(0.3f);
        int sessionMin = p.getFocusMinutes();
        int breakMin = p.getBreakMinutes();
        int maxDaily = p.getMaxDailyMinutes();
        float proIndex = p.getProcrastinationIndex();
        int deepLimit = proIndex > 0.6f ? 1 : 3;

        List<GoalTaskDto> sortedTasks = tasks.stream()
                .filter(t -> t != null && t.getId() != null)
                .sorted((a, b) -> {
                    int ap = a.getPriority() == null ? 0 : a.getPriority();
                    int bp = b.getPriority() == null ? 0 : b.getPriority();
                    if (bp != ap) return Integer.compare(bp, ap);
                    int am = a.getEstimatedMinutes() == null ? 30 : a.getEstimatedMinutes();
                    int bm = b.getEstimatedMinutes() == null ? 30 : b.getEstimatedMinutes();
                    return Integer.compare(bm, am);
                })
                .collect(Collectors.toList());

        List<FreeSlotDto> slots = freeSlots.stream()
                .filter(s -> s != null && s.getStart() != null && s.getEnd() != null && s.getEnd().isAfter(s.getStart()))
                .sorted((a, b) -> a.getStart().compareTo(b.getStart()))
                .collect(Collectors.toList());

        List<TaskScheduleDto> out = new ArrayList<>();
        int taskIdx = 0;
        int deepCount = 0;
        LocalDate current = slots.get(0).getStart().toLocalDate();
        int dayStudyMinutes = 0;

        for (FreeSlotDto slot : slots) {
            if (!slot.getStart().toLocalDate().equals(current)) {
                current = slot.getStart().toLocalDate();
                deepCount = 0;
                dayStudyMinutes = 0;
            }
            LocalDateTime cursor = slot.getStart();
            while (taskIdx < sortedTasks.size() && cursor.isBefore(slot.getEnd())) {
                GoalTaskDto t = sortedTasks.get(taskIdx);
                int minutes = t.getEstimatedMinutes() == null || t.getEstimatedMinutes() <= 0 ? 30 : t.getEstimatedMinutes();
                boolean isDeep = minutes >= 60;
                if (isDeep && deepCount >= deepLimit) {
                    taskIdx++;
                    continue;
                }
                if (dayStudyMinutes >= maxDaily) {
                    return out;
                }
                long remaining = java.time.Duration.between(cursor, slot.getEnd()).toMinutes();
                if (remaining < MIN_SLOT_MINUTES) {
                    break;
                }
                int planned = Math.min(minutes, sessionMin);
                int useMinutes = (int) Math.min(Math.max(MIN_SLOT_MINUTES, planned), remaining);
                int allowedByDay = maxDaily - dayStudyMinutes;
                useMinutes = Math.min(useMinutes, allowedByDay);
                if (useMinutes < MIN_SLOT_MINUTES) {
                    return out;
                }
                TaskScheduleDto s = new TaskScheduleDto();
                s.setTaskId(t.getId());
                s.setStartTime(cursor);
                s.setEndTime(cursor.plusMinutes(useMinutes));
                s.setStatus(0);
                out.add(s);
                dayStudyMinutes += useMinutes;
                if (isDeep && minutes >= 60) {
                    deepCount++;
                }
                cursor = s.getEndTime().plusMinutes(breakMin);
                taskIdx++;
            }
            if (taskIdx >= sortedTasks.size()) {
                break;
            }
        }
        return out;
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    @Data
    static class CandidateAiResponse {
        private String note;
        private List<FreeSlotDto> suggestedFreeSlots;
        private List<TaskScheduleDto> candidateSchedules;
        private List<TaskScheduleDto> suggestedSchedules;
    }
}
