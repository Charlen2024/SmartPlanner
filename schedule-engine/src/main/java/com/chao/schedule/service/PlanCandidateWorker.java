package com.chao.schedule.service;

import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.client.GoalClient;
import com.chao.common.dto.FreeSlotDto;
import com.chao.common.dto.GoalTaskDto;
import com.chao.common.dto.SchedulePreferenceDto;
import com.chao.common.dto.TaskScheduleDto;
import com.chao.schedule.entity.PlanCandidate;
import com.chao.schedule.config.ScheduleAiConfig;
import com.chao.schedule.config.ScheduleAiPrompts;
import com.chao.schedule.mapper.PlanCandidateMapper;
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
    private final PlanCandidateMapper planCandidateMapper;
    private final GoalClient goalClient;
    private final ScheduleAiConfig aiConfig;
    private final ScheduleAiPrompts aiPrompts;
    private final ScheduleUtils scheduleUtils;

    private static final int CANDIDATE_STATUS_READY = 0;

    @Async
    public void generate(Long candidateId, Long userId, LocalDate date, List<FreeSlotDto> freeSlots, List<GoalTaskDto> tasks, SchedulePreferenceDto pref) {
        SchedulePreferenceDto p = pref;
        if (p == null) {
            p = new SchedulePreferenceDto();
        }
        if (p.getFocusMinutes() == null || p.getFocusMinutes() <= 0) p.setFocusMinutes(aiConfig.getSessionMinutes());
        if (p.getBreakMinutes() == null || p.getBreakMinutes() <= 0) p.setBreakMinutes(aiConfig.getBreakMinutes());
        if (p.getMaxDailyMinutes() == null || p.getMaxDailyMinutes() <= 0) p.setMaxDailyMinutes(aiConfig.getMaxDailyMinutes());
        if (p.getProcrastinationIndex() == null) p.setProcrastinationIndex(aiConfig.getDefaultProcrastinationIndex());
        try {
            ScheduleUtils.CandidateAiResponse ai;
            try {
                String prompt = buildPlanPrompt(date, freeSlots, tasks, p);
                String aiJson = CompletableFuture
                        .supplyAsync(() -> openAiCompatClient.complete(prompt))
                        .orTimeout(aiConfig.getCandidateAiTimeoutSeconds(), TimeUnit.SECONDS)
                        .join();
                ai = scheduleUtils.parseCandidateAiResponse(aiJson);
            } catch (Exception e) {
                ai = new ScheduleUtils.CandidateAiResponse();
                ai.note = "已生成候选排程";
                ai.candidateSchedules = ruleBasedCandidateSchedules(freeSlots, tasks, p);
            }

            Map<Long, String> taskTitleMap = tasks.stream()
                    .filter(t -> t != null && t.getId() != null)
                    .collect(Collectors.toMap(GoalTaskDto::getId, GoalTaskDto::getTitle, (a, b) -> a));

            List<TaskScheduleDto> candidateSchedules = scheduleUtils.normalizeSchedules(ai.candidateSchedules);

            if (candidateSchedules.isEmpty()) {
                candidateSchedules = ruleBasedCandidateSchedules(freeSlots, tasks, p);
            }
            if (candidateSchedules.isEmpty()) {
                ai.note = "当前提供的空闲时间段太短，无法安排任何学习任务。建议增加更多可用时间（建议单段≥" + aiConfig.getMinSlotMinutes() + "分钟）。";
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
            ai.note = baseNote + "（当日空闲 " + freeCount + " 段 / " + freeMinutes + " 分钟，学习上限 " + aiConfig.getMaxDailyMinutes() + " 分钟）";

            for (TaskScheduleDto s : candidateSchedules) {
                if (s != null && s.getTaskId() != null && (s.getTaskTitle() == null || s.getTaskTitle().isBlank())) {
                    s.setTaskTitle(taskTitleMap.get(s.getTaskId()));
                }
            }

            PlanCandidate upd = new PlanCandidate();
            upd.setId(candidateId);
            upd.setStatus(CANDIDATE_STATUS_READY);
            upd.setNote(ai.note);
            upd.setSchedulesJson(scheduleUtils.writeJson(candidateSchedules));
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
        String freeJson = scheduleUtils.writeJson(freeSlots);
        List<Map<String, Object>> simpleTasks = tasks.stream().map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.getId());
            m.put("title", t.getTitle());
            m.put("estimatedMinutes", t.getEstimatedMinutes());
            m.put("priority", t.getPriority());
            return m;
        }).collect(Collectors.toList());
        String taskJson = scheduleUtils.writeJson(simpleTasks);
        String profileJson = scheduleUtils.writeJson(Map.of(
            "focusMinutes", p.getFocusMinutes(),
            "breakMinutes", p.getBreakMinutes(),
            "maxDailyMinutes", p.getMaxDailyMinutes(),
            "procrastinationIndex", p.getProcrastinationIndex()
        ));
        return aiPrompts.getCandidatePlanSystem() + "\nplanDate: " + date + "\nuserProfile: " + profileJson + "\nfreeSlots: " + freeJson + "\ntasks: " + taskJson;
    }

    private List<TaskScheduleDto> ruleBasedCandidateSchedules(List<FreeSlotDto> freeSlots, List<GoalTaskDto> tasks, SchedulePreferenceDto pref) {
        if (freeSlots == null || freeSlots.isEmpty() || tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        SchedulePreferenceDto p = pref != null ? pref : new SchedulePreferenceDto();
        if (p.getFocusMinutes() == null || p.getFocusMinutes() <= 0) p.setFocusMinutes(aiConfig.getSessionMinutes());
        if (p.getBreakMinutes() == null || p.getBreakMinutes() <= 0) p.setBreakMinutes(aiConfig.getBreakMinutes());
        if (p.getMaxDailyMinutes() == null || p.getMaxDailyMinutes() <= 0) p.setMaxDailyMinutes(aiConfig.getMaxDailyMinutes());
        if (p.getProcrastinationIndex() == null) p.setProcrastinationIndex(aiConfig.getDefaultProcrastinationIndex());
        int sessionMin = p.getFocusMinutes();
        int breakMin = p.getBreakMinutes();
        int maxDaily = p.getMaxDailyMinutes();
        float proIndex = p.getProcrastinationIndex();
        int deepLimit = proIndex > aiConfig.getProcrastinationThreshold() ? 1 : 3;

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
                if (remaining < aiConfig.getMinSlotMinutes()) {
                    break;
                }
                int planned = Math.min(minutes, sessionMin);
                int useMinutes = (int) Math.min(Math.max(aiConfig.getMinSlotMinutes(), planned), remaining);
                int allowedByDay = maxDaily - dayStudyMinutes;
                useMinutes = Math.min(useMinutes, allowedByDay);
                if (useMinutes < aiConfig.getMinSlotMinutes()) {
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

}
