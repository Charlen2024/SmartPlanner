package com.chao.schedule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.client.GoalClient;
import com.chao.common.dto.GoalTaskDto;
import com.chao.common.dto.FreeSlotDto;
import com.chao.common.dto.GoalDto;
import com.chao.common.dto.DailyPlanCommitRequest;
import com.chao.common.dto.DailyPlanCommitResponse;
import com.chao.common.dto.GeneratePlanCandidateRequest;
import com.chao.common.dto.PlanCandidateDto;
import com.chao.common.dto.TaskScheduleDto;
import com.chao.common.dto.Result;
import com.chao.common.dto.SchedulePreferenceDto;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.ScheduleImportResultDto;
import com.chao.schedule.entity.ClassSchedule;
import com.chao.schedule.entity.PlanCandidate;
import com.chao.schedule.entity.TaskSchedule;
import com.chao.schedule.entity.UserScheduleConfig;
import com.chao.schedule.mapper.ClassScheduleMapper;
import com.chao.schedule.mapper.PlanCandidateMapper;
import com.chao.schedule.mapper.TaskScheduleMapper;
import com.chao.schedule.config.ScheduleAiConfig;
import com.chao.schedule.config.ScheduleAiPrompts;
import com.chao.schedule.mapper.UserScheduleConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ClassScheduleMapper classScheduleMapper;
    private final TaskScheduleMapper taskScheduleMapper;
    private final PlanCandidateMapper planCandidateMapper;
    private final UserScheduleConfigMapper userScheduleConfigMapper;
    private final GoalClient goalClient;
    private final OpenAiCompatClient openAiCompatClient;
    private final ObjectMapper objectMapper;
    private final Executor aiTaskExecutor;
    private final PlanCandidateWorker planCandidateWorker;
    private final ScheduleAiConfig aiConfig;
    private final ScheduleAiPrompts aiPrompts;
    private final ScheduleUtils scheduleUtils;
    private final ScheduleValidator scheduleValidator;
    private final TaskScheduleService taskScheduleService;
    private final FreeTimeCalculator freeTimeCalculator;
    private final ScheduleImportService scheduleImportService;

    // Periods are structural (Chinese university schedule), not configurable
    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Shanghai");

    public SchedulePreferenceDto resolvePreference(SchedulePreferenceDto pref) {
        if (pref == null) {
            pref = new SchedulePreferenceDto();
        }
        if (pref.getFocusMinutes() == null || pref.getFocusMinutes() <= 0) {
            pref.setFocusMinutes(aiConfig.getSessionMinutes());
        }
        if (pref.getBreakMinutes() == null || pref.getBreakMinutes() <= 0) {
            pref.setBreakMinutes(aiConfig.getBreakMinutes());
        }
        if (pref.getMaxDailyMinutes() == null || pref.getMaxDailyMinutes() <= 0) {
            pref.setMaxDailyMinutes(aiConfig.getMaxDailyMinutes());
        }
        if (pref.getProcrastinationIndex() == null) {
            pref.setProcrastinationIndex(aiConfig.getDefaultProcrastinationIndex());
        }
        return pref;
    }

    private static final int CANDIDATE_STATUS_READY = 0;
    private static final int CANDIDATE_STATUS_ACCEPTED = 1;
    private static final int CANDIDATE_STATUS_REJECTED = 2;
    private static final int CANDIDATE_STATUS_GENERATING = 3;

    public ScheduleImportResultDto parseAndSaveSchedule(Long userId, MultipartFile file, String firstWeekMonday) {
        return scheduleImportService.parseAndSaveSchedule(userId, file, firstWeekMonday);
    }

    private boolean matchesWeek(ClassSchedule cs, int weekNumber) {
        return freeTimeCalculator.matchesWeek(cs, weekNumber);
    }

    public List<ClassSchedule> listClassSchedules(Long userId, Integer dayOfWeek, String date, String firstWeekMonday) {
        return freeTimeCalculator.listClassSchedules(userId, dayOfWeek, date, firstWeekMonday);
    }

    public void deleteClassSchedules(Long userId) {
        scheduleImportService.deleteClassSchedules(userId);
    }

    public void saveFirstWeekMonday(Long userId, LocalDate firstWeekMonday) {
        scheduleImportService.saveFirstWeekMonday(userId, firstWeekMonday);
    }

    public void clearFirstWeekMonday(Long userId) {
        scheduleImportService.clearFirstWeekMonday(userId);
    }

    public List<ScheduleClient.TimeSlot> calculateFreeTime(Long userId, String dateStr) {
        return freeTimeCalculator.calculateFreeTime(userId, dateStr);
    }

    public List<ScheduleClient.TimeSlot> calculateFreeTime(Long userId, String dateStr, String firstWeekMonday) {
        return freeTimeCalculator.calculateFreeTime(userId, dateStr, firstWeekMonday);
    }

    @lombok.AllArgsConstructor
    static class TimeRange {
        LocalTime start;
        LocalTime end;
    }

    @lombok.AllArgsConstructor
    static class TimePoint {
        LocalTime time;
        boolean isStart;
    }

    public void smartSchedule(Long userId) {
        log.info("开始为用户 {} 进行智能排程", userId);
        
        // 1. 获取待办任务
        Result<List<GoalTaskDto>> tasksResult = goalClient.getPendingTasks(userId);
        if (tasksResult.getCode() != 200 || tasksResult.getData().isEmpty()) {
            return;
        }
        List<GoalTaskDto> tasks = tasksResult.getData();

        // 2. 获取未来 3 天的空闲时段
        List<ScheduleClient.TimeSlot> allFreeSlots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            allFreeSlots.addAll(calculateFreeTime(userId, LocalDate.now(APP_ZONE).plusDays(i).toString()));
        }

        // 3. 调用 AI 决策
        String taskListStr = tasks.stream()
                .map(t -> String.format("ID:%d, Title:%s, Duration:%dmin, Priority:%d", t.getId(), t.getTitle(), t.getEstimatedMinutes(), t.getPriority()))
                .collect(Collectors.joining("; "));
        
        String slotsStr = allFreeSlots.stream()
                .map(s -> String.format("[%s - %s]", s.getStart(), s.getEnd()))
                .collect(Collectors.joining(", "));

        String prompt = String.format("""
            用户有以下待办任务：%s
            未来空闲时段有：%s
            请根据任务优先级和耗时，将任务合理分配到空闲时段。
            请直接返回 JSON 数组格式，不要有 Markdown 格式或额外文字。
            JSON 结构示例: [{"taskId": 1, "startTime": "2026-04-10T14:00:00", "endTime": "2026-04-10T15:00:00"}]
            """, taskListStr, slotsStr);

        try {
            String response;
            try {
                response = CompletableFuture
                        .supplyAsync(() -> openAiCompatClient.complete(prompt))
                        .orTimeout(Math.max(5, aiConfig.getScheduleAiTimeoutSeconds()), TimeUnit.SECONDS)
                        .join();
                log.info("AI 智能排程决策: {}", response);
            } catch (Exception aiEx) {
                log.error("AI 排程调用失败，使用本地降级方案: {}", aiEx.getMessage());
                response = writeJson(buildLocalSchedules(tasks, allFreeSlots));
            }

            // 4. 解析并保存决策结果
            List<TaskSchedule> schedules = objectMapper.readValue(response, new TypeReference<List<TaskSchedule>>() {});
            
            // 清理未来 3 天的旧排程，避免重复
            LocalDateTime from = LocalDate.now(APP_ZONE).atStartOfDay();
            taskScheduleMapper.delete(new LambdaQueryWrapper<TaskSchedule>()
                    .eq(TaskSchedule::getUserId, userId)
                    .ge(TaskSchedule::getStartTime, from)
                    .eq(TaskSchedule::getStatus, 0));

            for (TaskSchedule s : schedules) {
                s.setUserId(userId);
                s.setStatus(0); // 未开始
                taskScheduleMapper.insert(s);
                
                // 更新任务状态为"进行中" (1)
                goalClient.updateTaskStatus(s.getTaskId(), 1);
            }
        } catch (Exception e) {
            log.error("智能排程处理失败", e);
        }
    }

    public void smartScheduleAsync(Long userId) {
        CompletableFuture.runAsync(() -> smartSchedule(userId), aiTaskExecutor);
    }

    public PlanCandidateDto generatePlanCandidate(Long userId, GeneratePlanCandidateRequest request) {
        LocalDate date = request != null && request.getDate() != null ? request.getDate() : LocalDate.now(APP_ZONE);
        List<FreeSlotDto> inputSlots = request != null ? request.getFreeSlots() : null;
        List<FreeSlotDto> normalizedSlots = normalizeSlots(date, inputSlots);
        if (normalizedSlots.isEmpty()) {
            Long cnt = classScheduleMapper.selectCount(new LambdaQueryWrapper<ClassSchedule>().eq(ClassSchedule::getUserId, userId));
            if (cnt == null || cnt <= 0) {
                throw new IllegalArgumentException("请先导入/更新课表后再生成计划");
            }
            normalizedSlots = calculateFreeTime(userId, date.toString()).stream().map(s -> {
                FreeSlotDto dto = new FreeSlotDto();
                dto.setStart(s.getStart());
                dto.setEnd(s.getEnd());
                return dto;
            }).collect(Collectors.toList());
        }
        if (normalizedSlots.isEmpty()) {
            throw new IllegalArgumentException("当天无可用空闲时间段");
        }

        Result<List<GoalTaskDto>> taskRes = goalClient.getPendingTasks(userId);
        List<GoalTaskDto> tasks = taskRes != null && taskRes.getCode() == 200 && taskRes.getData() != null ? taskRes.getData() : List.of();
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("暂无可排程任务，请先生成学习目标与任务");
        }

        PlanCandidate entity = new PlanCandidate();
        entity.setUserId(userId);
        entity.setPlanDate(date);
        entity.setStatus(CANDIDATE_STATUS_GENERATING);
        entity.setNote("AI 生成中…");
        entity.setFreeSlotsJson(writeJson(normalizedSlots));
        entity.setSuggestedFreeSlotsJson(null);
        entity.setSchedulesJson("[]");
        entity.setSuggestedSchedulesJson(null);
        entity.setCreatedAt(LocalDateTime.now(APP_ZONE));
        planCandidateMapper.insert(entity);

        planCandidateWorker.generate(entity.getId(), userId, date, normalizedSlots, tasks, resolvePreference(request != null ? request.getPreference() : null));

        PlanCandidateDto dto = new PlanCandidateDto();
        dto.setId(entity.getId());
        dto.setUserId(userId);
        dto.setPlanDate(date);
        dto.setStatus(entity.getStatus());
        dto.setNote(entity.getNote());
        dto.setFreeSlots(normalizedSlots);
        dto.setSuggestedFreeSlots(List.of());
        dto.setSchedules(List.of());
        dto.setSuggestedSchedules(List.of());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    @FunctionalInterface
    public interface ProgressReporter {
        void report(String stage, int progress, String message);
    }

    public DailyPlanCommitResponse commitDailyPlan(Long userId, DailyPlanCommitRequest request) {
        return commitDailyPlan(userId, request, null);
    }

    public DailyPlanCommitResponse commitDailyPlan(Long userId, DailyPlanCommitRequest request, ProgressReporter reporter) {
        LocalDate date = request != null && request.getDate() != null ? request.getDate() : LocalDate.now(APP_ZONE);
        SchedulePreferenceDto pref = resolvePreference(request != null ? request.getPreference() : null);
        String mode = request != null && request.getMode() != null ? request.getMode().trim().toLowerCase(Locale.ROOT) : "replace";
        int sessionMin = pref.getFocusMinutes();
        int breakMin = pref.getBreakMinutes();
        int maxDaily = pref.getMaxDailyMinutes();
        float proIndex = pref.getProcrastinationIndex();
        if ("merge".equals(mode)) {
            mode = "append";
        }
        if (!"append".equals(mode) && !"replace".equals(mode)) {
            mode = "replace";
        }
        Long scopeGoalId = request != null ? request.getGoalId() : null;
        List<Long> scopeTaskIds = request != null ? request.getTaskIds() : null;
        Set<Long> scopeTaskIdSet = scopeTaskIds == null ? Set.of() : scopeTaskIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        report(reporter, "PREPARE", 5, "正在计算空闲时间");

        List<FreeSlotDto> freeSlots = calculateFreeTime(userId, date.toString()).stream().map(s -> {
            FreeSlotDto dto = new FreeSlotDto();
            dto.setStart(s.getStart());
            dto.setEnd(s.getEnd());
            return dto;
        }).collect(Collectors.toList());

        if (freeSlots.isEmpty()) {
            throw new IllegalArgumentException("当天无可用空闲时间段");
        }

        report(reporter, "FETCH_TASKS", 15, "正在获取待办任务");
        List<GoalTaskDto> tasks = List.of();
        boolean scoped = (scopeGoalId != null) || (scopeTaskIdSet != null && !scopeTaskIdSet.isEmpty());
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Result<List<GoalTaskDto>> taskRes = goalClient.getPendingTasks(userId);
                tasks = taskRes != null && taskRes.getCode() == 200 && taskRes.getData() != null ? taskRes.getData() : List.of();
            } catch (Exception ignored) {
                tasks = List.of();
            }
            tasks = tasks != null ? tasks : List.of();
            if (!tasks.isEmpty()) {
                break;
            }
            if (attempt < 4) {
                report(reporter, "WAIT_TASKS", 15 + (attempt + 1) * 2, "任务生成可能有延迟，正在重试获取任务（" + (attempt + 1) + "/5）");
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (!tasks.isEmpty()) {
            if (scopeTaskIdSet != null && !scopeTaskIdSet.isEmpty()) {
                tasks = tasks.stream()
                        .filter(t -> t != null && t.getId() != null)
                        .filter(t -> scopeTaskIdSet.contains(t.getId()))
                        .collect(Collectors.toList());
            } else if (scopeGoalId != null) {
                tasks = tasks.stream()
                        .filter(t -> t != null && t.getId() != null)
                        .filter(t -> Objects.equals(t.getGoalId(), scopeGoalId))
                        .collect(Collectors.toList());
            }
        }

        if (tasks.isEmpty() && !scoped) {
            boolean created = false;
            try {
                Result<List<GoalDto>> goalsRes = goalClient.listGoals(userId);
                List<GoalDto> goals = goalsRes != null && goalsRes.getCode() == 200 && goalsRes.getData() != null ? goalsRes.getData() : List.of();
                if (goals.isEmpty()) {
                    report(reporter, "AUTO_CREATE_GOAL", 22, "未检测到学习目标，正在自动生成目标与任务");
                    List<ClassSchedule> allClasses = classScheduleMapper.selectList(new LambdaQueryWrapper<ClassSchedule>().eq(ClassSchedule::getUserId, userId));
                    String courseText = allClasses == null ? "" : allClasses.stream()
                            .filter(c -> c != null && c.getCourseName() != null && !c.getCourseName().isBlank())
                            .map(ClassSchedule::getCourseName)
                            .distinct()
                            .limit(6)
                            .collect(Collectors.joining("、"));
                    String desc = courseText.isBlank()
                            ? "请为我生成一个本周学习计划目标，并拆解为可执行任务。"
                            : "请为我生成本周学习计划，围绕课程：" + courseText + "，拆解为可执行任务。";
                    goalClient.createGoalByAi(userId, desc);
                    created = true;
                } else {
                    GoalDto latest = goals.stream()
                            .filter(g -> g != null && g.getId() != null)
                            .max((a, b) -> {
                                if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
                                if (a.getCreatedAt() == null) return -1;
                                if (b.getCreatedAt() == null) return 1;
                                return a.getCreatedAt().compareTo(b.getCreatedAt());
                            })
                            .orElse(null);
                    if (latest != null) {
                        report(reporter, "AUTO_CREATE_TASKS", 22, "检测到学习目标但暂无待办任务，正在自动生成任务");
                        goalClient.regenerateTasks(latest.getId(), userId, "请生成可排程的学习任务清单");
                        created = true;
                    }
                }
            } catch (Exception ignored) {
            }

            if (created) {
                for (int attempt = 0; attempt < 120; attempt++) {
                    try {
                        Result<List<GoalTaskDto>> taskRes = goalClient.getPendingTasks(userId);
                        tasks = taskRes != null && taskRes.getCode() == 200 && taskRes.getData() != null ? taskRes.getData() : List.of();
                    } catch (Exception ignored) {
                        tasks = List.of();
                    }
                    tasks = tasks != null ? tasks : List.of();
                    if (!tasks.isEmpty()) {
                        break;
                    }
                    int p = Math.min(44, 22 + (attempt + 1) / 3);
                    report(reporter, "WAIT_TASKS", p, "正在等待任务生成完成（" + (attempt + 1) + "/120）");
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (tasks.isEmpty()) {
            if (scopeGoalId != null) {
                throw new IllegalArgumentException("所选目标暂无可排程任务（可能任务生成中或已全部完成），请更换目标或稍后再试");
            }
            if (scopeTaskIdSet != null && !scopeTaskIdSet.isEmpty()) {
                throw new IllegalArgumentException("所选任务暂无可排程项（可能已排程/已完成），请更换任务或稍后再试");
            }
            throw new IllegalArgumentException("暂无可排程任务（可能任务生成中），请先生成学习目标与任务，或稍后再试");
        }

        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        List<TaskSchedule> existing = taskScheduleMapper.selectList(new LambdaQueryWrapper<TaskSchedule>()
                .eq(TaskSchedule::getUserId, userId)
                .ge(TaskSchedule::getStartTime, dayStart)
                .lt(TaskSchedule::getStartTime, dayEnd)
                .orderByAsc(TaskSchedule::getStartTime));

        if ("replace".equals(mode)) {
            report(reporter, "CLEAR_EXISTING", 25, "正在清理当天未完成排程");
            LambdaQueryWrapper<TaskSchedule> del = new LambdaQueryWrapper<TaskSchedule>()
                    .eq(TaskSchedule::getUserId, userId)
                    .ge(TaskSchedule::getStartTime, dayStart)
                    .lt(TaskSchedule::getStartTime, dayEnd)
                    .eq(TaskSchedule::getStatus, 0);
            List<Long> scopeIds = tasks.stream()
                    .filter(t -> t != null && t.getId() != null)
                    .map(GoalTaskDto::getId)
                    .distinct()
                    .collect(Collectors.toList());
            if (scoped && !scopeIds.isEmpty()) {
                del.in(TaskSchedule::getTaskId, scopeIds);
            }
            taskScheduleMapper.delete(del);
            existing = taskScheduleMapper.selectList(new LambdaQueryWrapper<TaskSchedule>()
                    .eq(TaskSchedule::getUserId, userId)
                    .ge(TaskSchedule::getStartTime, dayStart)
                    .lt(TaskSchedule::getStartTime, dayEnd)
                    .orderByAsc(TaskSchedule::getStartTime));
        }

        List<Long> alreadyScheduledTaskIds = existing == null ? List.of() : existing.stream()
                .map(TaskSchedule::getTaskId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        List<GoalTaskDto> remainingTasks = tasks.stream()
                .filter(t -> t != null && t.getId() != null)
                .filter(t -> !alreadyScheduledTaskIds.contains(t.getId()))
                .collect(Collectors.toList());

        if (remainingTasks.isEmpty()) {
            DailyPlanCommitResponse resp = new DailyPlanCommitResponse();
            resp.setDate(date);
            resp.setMode(mode);
            resp.setNote("当天暂无可新增排程任务");
            resp.setFreeSlots(freeSlots);
            resp.setSchedules(listTaskSchedules(userId, dayStart, dayEnd));
            return resp;
        }

        List<FreeSlotDto> remainingFree = subtractOccupied(freeSlots, existing);
        if (remainingFree.isEmpty()) {
            throw new IllegalArgumentException("当天空闲时间已被占满");
        }

        CommitAiResponse ai;
        try {
            report(reporter, "CALL_AI", 45, "正在调用模型进行智能排程");
            String prompt = buildDailyPlanPrompt(date, remainingFree, remainingTasks, pref);
            log.info("调用模型: userId={}, date={}, freeSlots={}, tasks={}", userId, date, remainingFree != null ? remainingFree.size() : 0, remainingTasks != null ? remainingTasks.size() : 0);
            String aiJson = CompletableFuture
                    .supplyAsync(() -> openAiCompatClient.complete(prompt))
                    .orTimeout(Math.max(5, aiConfig.getScheduleAiTimeoutSeconds()), TimeUnit.SECONDS)
                    .join();
            log.info("智能排程模型返回长度: {}", aiJson != null ? aiJson.length() : 0);
            if (aiJson != null) {
                String preview = aiJson.length() <= 900 ? aiJson : aiJson.substring(0, 900);
                log.info("智能排程模型返回预览: {}", preview);
            }
            ai = parseCommitAiResponse(aiJson);
        } catch (Exception e) {
            report(reporter, "FALLBACK", 55, "智能排程失败，正在使用规则降级排程");
            log.warn("智能排程调用失败: {}", e.getMessage());
            ai = new CommitAiResponse();
            ai.note = "已生成当日计划（规则）";
            ai.candidateSchedules = planCandidateWorker.generateRuleBasedSchedules(remainingFree, remainingTasks, pref);
        }

        List<TaskScheduleDto> candidate = ai.candidateSchedules != null ? ai.candidateSchedules : List.of();
        candidate = candidate.stream().filter(s -> s != null && s.getTaskId() != null && s.getStartTime() != null && s.getEndTime() != null).collect(Collectors.toList());
        candidate = normalizeTaskIds(candidate, remainingTasks, reporter);
        candidate = filterOverlaps(candidate);
        candidate = enforceMinGap(candidate, breakMin);
        candidate = clampDailyMinutes(candidate, maxDaily);

        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("当前空闲时间段不足以安排任务，请增加时间段后重试");
        }
        report(reporter, "VALIDATE", 70, "正在校验冲突与总时长");
        if (!allWithinFreeSlots(candidate, remainingFree)) {
            throw new IllegalArgumentException("排程与课表/空闲时间冲突，已拒绝写入");
        }

        report(reporter, "WRITE_DB", 85, "正在写入日程");
        for (TaskScheduleDto s : candidate) {
            TaskSchedule ts = new TaskSchedule();
            ts.setUserId(userId);
            ts.setTaskId(s.getTaskId());
            ts.setStartTime(s.getStartTime());
            ts.setEndTime(s.getEndTime());
            ts.setStatus(0);
            taskScheduleMapper.insert(ts);
            try {
                goalClient.updateTaskStatus(s.getTaskId(), 1);
            } catch (Exception ignored) {
            }
        }

        try {
            report(reporter, "ASSIGN_GOALS", 92, "正在归并任务到最相近目标");
            assignMissingGoalIds(userId, candidate, reporter);
        } catch (Exception e) {
            log.warn("任务归并到目标失败: {}", e.getMessage());
        }

        DailyPlanCommitResponse resp = new DailyPlanCommitResponse();
        resp.setDate(date);
        resp.setMode(mode);
        resp.setNote(ai.note == null || ai.note.isBlank() ? "已生成当日计划" : ai.note);
        resp.setFreeSlots(freeSlots);
        resp.setSchedules(listTaskSchedules(userId, dayStart, dayEnd));
        report(reporter, "DONE", 100, "已完成");
        return resp;
    }

    private void report(ProgressReporter reporter, String stage, int progress, String message) {
        if (reporter == null) {
            return;
        }
        try {
            log.info("排程进度 stage={} progress={} message={}", stage, progress, message);
        } catch (Exception ignored) {
        }
        try {
            reporter.report(stage, progress, message);
        } catch (Exception ignored) {
        }
    }

    private List<TaskScheduleDto> normalizeTaskIds(List<TaskScheduleDto> candidate, List<GoalTaskDto> tasks, ProgressReporter reporter) {
        if (candidate == null || candidate.isEmpty() || tasks == null || tasks.isEmpty()) {
            return candidate;
        }
        Map<Long, GoalTaskDto> byId = tasks.stream()
                .filter(t -> t != null && t.getId() != null)
                .collect(Collectors.toMap(GoalTaskDto::getId, t -> t, (a, b) -> a));

        List<GoalTaskDto> ordered = tasks.stream()
                .filter(t -> t != null && t.getId() != null)
                .sorted((a, b) -> {
                    int ap = a.getPriority() != null ? a.getPriority() : 0;
                    int bp = b.getPriority() != null ? b.getPriority() : 0;
                    if (ap != bp) return Integer.compare(bp, ap);
                    int am = a.getEstimatedMinutes() != null ? a.getEstimatedMinutes() : 0;
                    int bm = b.getEstimatedMinutes() != null ? b.getEstimatedMinutes() : 0;
                    if (am != bm) return Integer.compare(bm, am);
                    return Long.compare(a.getId(), b.getId());
                })
                .collect(Collectors.toList());

        int changed = 0;
        for (TaskScheduleDto s : candidate) {
            if (s == null || s.getTaskId() == null) {
                continue;
            }
            if (byId.containsKey(s.getTaskId())) {
                continue;
            }
            GoalTaskDto mapped = null;
            if (s.getTaskTitle() != null && !s.getTaskTitle().isBlank()) {
                mapped = bestMatchByTitle(s.getTaskTitle(), ordered);
            }
            long raw = s.getTaskId();
            if (raw >= 1 && raw <= ordered.size()) {
                mapped = ordered.get((int) raw - 1);
            }
            if (mapped == null && !ordered.isEmpty()) {
                mapped = ordered.get(0);
            }
            if (mapped != null) {
                s.setTaskId(mapped.getId());
                s.setTaskTitle(mapped.getTitle());
                changed++;
            }
        }
        if (changed > 0) {
            report(reporter, "MAP_TASKS", 60, "模型返回的任务编号与系统不一致，已自动纠正 " + changed + " 条任务归属");
        }
        return candidate;
    }

    private GoalTaskDto bestMatchByTitle(String title, List<GoalTaskDto> tasks) {
        if (title == null || title.isBlank() || tasks == null || tasks.isEmpty()) {
            return null;
        }
        String q = normalizeText(title);
        GoalTaskDto best = null;
        double bestScore = 0.0;
        for (GoalTaskDto t : tasks) {
            if (t == null || t.getTitle() == null) continue;
            String cand = normalizeText(t.getTitle());
            double score = textSimilarity(q, cand);
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        if (bestScore >= 0.2) {
            return best;
        }
        return null;
    }

    private void assignMissingGoalIds(Long userId, List<TaskScheduleDto> candidate, ProgressReporter reporter) {
        if (candidate == null || candidate.isEmpty()) {
            return;
        }
        List<Long> taskIds = candidate.stream()
                .filter(s -> s != null && s.getTaskId() != null)
                .map(TaskScheduleDto::getTaskId)
                .distinct()
                .collect(Collectors.toList());
        if (taskIds.isEmpty()) {
            return;
        }

        List<GoalTaskDto> tasks = List.of();
        try {
            Result<List<GoalTaskDto>> r = goalClient.getTasksByIds(taskIds);
            tasks = r != null && r.getCode() == 200 && r.getData() != null ? r.getData() : List.of();
        } catch (Exception ignored) {
        }
        List<GoalTaskDto> missing = tasks.stream()
                .filter(t -> t != null && t.getId() != null && t.getGoalId() == null)
                .collect(Collectors.toList());
        if (missing.isEmpty()) {
            return;
        }

        List<GoalDto> goals = List.of();
        try {
            Result<List<GoalDto>> g = goalClient.listGoals(userId);
            goals = g != null && g.getCode() == 200 && g.getData() != null ? g.getData() : List.of();
        } catch (Exception ignored) {
        }
        if (goals.isEmpty()) {
            return;
        }

        Map<Long, Long> mapping = null;
        try {
            mapping = assignByAi(goals, missing);
        } catch (Exception ignored) {
        }
        if (mapping == null || mapping.isEmpty()) {
            mapping = assignByRule(goals, missing);
        }

        int moved = 0;
        for (GoalTaskDto t : missing) {
            Long gid = mapping.get(t.getId());
            if (gid == null) continue;
            boolean exists = goals.stream().anyMatch(g -> g != null && g.getId() != null && g.getId().equals(gid));
            if (!exists) continue;
            try {
                goalClient.moveTaskToGoal(userId, t.getId(), gid);
                moved++;
            } catch (Exception ignored) {
            }
        }
        if (moved > 0) {
            report(reporter, "ASSIGN_GOALS", 98, "已归并 " + moved + " 个任务到目标");
        }
    }

    private Map<Long, Long> assignByAi(List<GoalDto> goals, List<GoalTaskDto> tasks) {
        List<Map<String, Object>> goalList = goals.stream()
                .filter(g -> g != null && g.getId() != null)
                .map(g -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", g.getId());
                    m.put("title", g.getTitle());
                    m.put("description", g.getDescription());
                    return m;
                }).collect(Collectors.toList());
        List<Map<String, Object>> taskList = tasks.stream()
                .filter(t -> t != null && t.getId() != null)
                .map(t -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", t.getId());
                    m.put("title", t.getTitle());
                    m.put("description", t.getDescription());
                    return m;
                }).collect(Collectors.toList());

        String prompt = ""
                + "你是目标归并助手。你将收到 goals（目标列表）与 tasks（未归属目标的任务）。\n"
                + "请为每个 task 选择一个最相近的 goal，并输出严格 JSON 对象：key 为 taskId（数字），value 为 goalId（数字）。\n"
                + "只输出 JSON，不要 Markdown，不要额外文字。\n\n"
                + "goals: " + writeJson(goalList) + "\n"
                + "tasks: " + writeJson(taskList) + "\n";

        String text = openAiCompatClient.complete(prompt);
        String json = sanitizeJsonObject(text);
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
            Map<Long, Long> out = new HashMap<>();
            root.fields().forEachRemaining(e -> {
                try {
                    Long taskId = Long.valueOf(e.getKey());
                    Long goalId = e.getValue().asLong();
                    out.put(taskId, goalId);
                } catch (Exception ignored) {
                }
            });
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<Long, Long> assignByRule(List<GoalDto> goals, List<GoalTaskDto> tasks) {
        Map<Long, Long> out = new HashMap<>();
        for (GoalTaskDto t : tasks) {
            if (t == null || t.getId() == null) continue;
            GoalDto best = null;
            double bestScore = 0.0;
            String q = normalizeText((t.getTitle() != null ? t.getTitle() : "") + " " + (t.getDescription() != null ? t.getDescription() : ""));
            for (GoalDto g : goals) {
                if (g == null || g.getId() == null) continue;
                String cand = normalizeText((g.getTitle() != null ? g.getTitle() : "") + " " + (g.getDescription() != null ? g.getDescription() : ""));
                double score = textSimilarity(q, cand);
                if (score > bestScore) {
                    bestScore = score;
                    best = g;
                }
            }
            if (best != null) {
                out.put(t.getId(), best.getId());
            }
        }
        return out;
    }

    private String normalizeText(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private double textSimilarity(String a, String b) {
        if (a.isBlank() || b.isBlank()) {
            return 0.0;
        }
        if (a.contains(b) || b.contains(a)) {
            return 1.0;
        }
        java.util.Set<Integer> sa = a.chars().boxed().collect(java.util.stream.Collectors.toSet());
        java.util.Set<Integer> sb = b.chars().boxed().collect(java.util.stream.Collectors.toSet());
        int inter = 0;
        for (Integer c : sa) {
            if (sb.contains(c)) inter++;
        }
        int union = sa.size() + sb.size() - inter;
        return union == 0 ? 0.0 : (inter * 1.0 / union);
    }

    public void decidePlanCandidate(Long userId, Long candidateId, Boolean accept, Boolean useSuggestedSlots) {
        PlanCandidate c = planCandidateMapper.selectById(candidateId);
        if (c == null || c.getUserId() == null || !c.getUserId().equals(userId)) {
            throw new IllegalArgumentException("候选计划不存在");
        }
        if (c.getStatus() != null && c.getStatus() == CANDIDATE_STATUS_GENERATING) {
            throw new IllegalArgumentException("候选排程生成中，请稍后再试");
        }
        if (c.getStatus() != null && c.getStatus() != CANDIDATE_STATUS_READY) {
            return;
        }
        if (accept == null || !accept) {
            PlanCandidate upd = new PlanCandidate();
            upd.setId(candidateId);
            upd.setStatus(CANDIDATE_STATUS_REJECTED);
            planCandidateMapper.updateById(upd);
            return;
        }

        List<TaskScheduleDto> schedules = readJsonList(c.getSchedulesJson(), new TypeReference<List<TaskScheduleDto>>() {});
        schedules = schedules != null ? schedules : List.of();
        List<TaskScheduleDto> suggestedSchedules = readJsonList(c.getSuggestedSchedulesJson(), new TypeReference<List<TaskScheduleDto>>() {});
        suggestedSchedules = suggestedSchedules != null ? suggestedSchedules : List.of();

        boolean useSuggested = useSuggestedSlots != null && useSuggestedSlots && !suggestedSchedules.isEmpty();
        List<TaskScheduleDto> decided = useSuggested ? suggestedSchedules : schedules;
        List<FreeSlotDto> allowedSlots = useSuggested
                ? readJsonList(c.getSuggestedFreeSlotsJson(), new TypeReference<List<FreeSlotDto>>() {})
                : readJsonList(c.getFreeSlotsJson(), new TypeReference<List<FreeSlotDto>>() {});
        allowedSlots = allowedSlots != null ? allowedSlots : List.of();

        List<TaskScheduleDto> filtered = filterOverlaps(decided);
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException("当前空闲时间段不足以安排任务，请增加时间段后重试");
        }
        if (!allowedSlots.isEmpty() && !allWithinFreeSlots(filtered, allowedSlots)) {
            throw new IllegalArgumentException("排程与课表/空闲时间冲突，已拒绝写入");
        }

        LocalDate date = c.getPlanDate() != null ? c.getPlanDate() : LocalDate.now(APP_ZONE);
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);

        taskScheduleMapper.delete(new LambdaQueryWrapper<TaskSchedule>()
                .eq(TaskSchedule::getUserId, userId)
                .ge(TaskSchedule::getStartTime, dayStart)
                .lt(TaskSchedule::getStartTime, dayEnd)
                .eq(TaskSchedule::getStatus, 0));

        for (TaskScheduleDto s : filtered) {
            TaskSchedule ts = new TaskSchedule();
            ts.setUserId(userId);
            ts.setTaskId(s.getTaskId());
            ts.setStartTime(s.getStartTime());
            ts.setEndTime(s.getEndTime());
            ts.setStatus(0);
            taskScheduleMapper.insert(ts);
        }

        PlanCandidate upd = new PlanCandidate();
        upd.setId(candidateId);
        upd.setStatus(CANDIDATE_STATUS_ACCEPTED);
        planCandidateMapper.updateById(upd);
    }

    public List<PlanCandidateDto> listPlanCandidates(Long userId, String date) {
        LambdaQueryWrapper<PlanCandidate> qw = new LambdaQueryWrapper<PlanCandidate>()
                .eq(PlanCandidate::getUserId, userId)
                .orderByDesc(PlanCandidate::getCreatedAt);
        if (date != null && !date.isBlank()) {
            qw.eq(PlanCandidate::getPlanDate, LocalDate.parse(date.trim()));
        }
        List<PlanCandidate> list = planCandidateMapper.selectList(qw);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<PlanCandidateDto> out = new ArrayList<>();
        for (PlanCandidate c : list) {
            PlanCandidateDto dto = new PlanCandidateDto();
            dto.setId(c.getId());
            dto.setUserId(c.getUserId());
            dto.setPlanDate(c.getPlanDate());
            dto.setStatus(c.getStatus());
            dto.setNote(c.getNote());
            dto.setFreeSlots(readJsonList(c.getFreeSlotsJson(), new TypeReference<List<FreeSlotDto>>() {}));
            dto.setSuggestedFreeSlots(readJsonList(c.getSuggestedFreeSlotsJson(), new TypeReference<List<FreeSlotDto>>() {}));
            dto.setSchedules(readJsonList(c.getSchedulesJson(), new TypeReference<List<TaskScheduleDto>>() {}));
            dto.setSuggestedSchedules(readJsonList(c.getSuggestedSchedulesJson(), new TypeReference<List<TaskScheduleDto>>() {}));
            dto.setCreatedAt(c.getCreatedAt());
            out.add(dto);
        }
        return out;
    }

    List<TaskScheduleDto> filterOverlaps(List<TaskScheduleDto> schedules) {
        return scheduleValidator.filterOverlaps(schedules);
    }

    List<TaskScheduleDto> enforceMinGap(List<TaskScheduleDto> schedules, int gapMinutes) {
        return scheduleValidator.enforceMinGap(schedules, gapMinutes);
    }

    List<TaskScheduleDto> clampDailyMinutes(List<TaskScheduleDto> schedules, int maxMinutes) {
        return scheduleValidator.clampDailyMinutes(schedules, maxMinutes);
    }

    List<FreeSlotDto> subtractOccupied(List<FreeSlotDto> freeSlots, List<TaskSchedule> occupiedSchedules) {
        return scheduleValidator.subtractOccupied(freeSlots, occupiedSchedules, aiConfig.getBreakMinutes());
    }

    private String buildDailyPlanPrompt(LocalDate date, List<FreeSlotDto> freeSlots, List<GoalTaskDto> tasks, SchedulePreferenceDto pref) {
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
        SchedulePreferenceDto p = resolvePreference(pref);
        String profileJson = writeJson(Map.of(
            "focusMinutes", p.getFocusMinutes(),
            "breakMinutes", p.getBreakMinutes(),
            "maxDailyMinutes", p.getMaxDailyMinutes(),
            "procrastinationIndex", p.getProcrastinationIndex()
        ));
        return aiPrompts.getDailyPlanSystem() + "\nplanDate: " + date + "\nuserProfile: " + profileJson + "\nfreeSlots: " + freeJson + "\ntasks: " + taskJson;
    }

    private CommitAiResponse parseCommitAiResponse(String aiJson) {
        try {
            String sanitized = sanitizeJsonObject(aiJson);
            return objectMapper.readValue(sanitized, CommitAiResponse.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("候选计划解析失败");
        }
    }

    String sanitizeJsonObject(String text) {
        return scheduleUtils.sanitizeJsonObject(text);
    }

    @lombok.Data
    static class CommitAiResponse {
        private String note;
        private List<TaskScheduleDto> candidateSchedules;
    }

    boolean allWithinFreeSlots(List<TaskScheduleDto> schedules, List<FreeSlotDto> freeSlots) {
        return scheduleValidator.allWithinFreeSlots(schedules, freeSlots);
    }

    List<FreeSlotDto> normalizeSlots(LocalDate date, List<FreeSlotDto> slots) {
        return scheduleUtils.normalizeSlots(date, slots);
    }

    List<TaskScheduleDto> normalizeSchedules(List<TaskScheduleDto> schedules) {
        return scheduleUtils.normalizeSchedules(schedules);
    }

    private String buildPlanPrompt(LocalDate date, List<FreeSlotDto> freeSlots, List<GoalTaskDto> tasks, SchedulePreferenceDto pref) {
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
        SchedulePreferenceDto p = resolvePreference(pref);
        String profileJson = writeJson(Map.of(
            "focusMinutes", p.getFocusMinutes(),
            "breakMinutes", p.getBreakMinutes(),
            "maxDailyMinutes", p.getMaxDailyMinutes(),
            "procrastinationIndex", p.getProcrastinationIndex()
        ));
        return aiPrompts.getPlanSystem() + "\nplanDate: " + date + "\nuserProfile: " + profileJson + "\nfreeSlots: " + freeJson + "\ntasks: " + taskJson;
    }

    private ScheduleUtils.CandidateAiResponse parseCandidateAiResponse(String aiJson) {
        return scheduleUtils.parseCandidateAiResponse(aiJson);
    }

    private List<TaskSchedule> buildLocalSchedules(List<GoalTaskDto> tasks, List<ScheduleClient.TimeSlot> slots) {
        if (tasks == null || tasks.isEmpty() || slots == null || slots.isEmpty()) {
            return List.of();
        }
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

        List<TaskSchedule> out = new ArrayList<>();
        int taskIdx = 0;
        int deepCount = 0;

        for (ScheduleClient.TimeSlot slot : slots) {
            if (slot == null || slot.getStart() == null || slot.getEnd() == null || !slot.getEnd().isAfter(slot.getStart())) {
                continue;
            }
            LocalDateTime cursor = slot.getStart();
            while (taskIdx < sortedTasks.size() && cursor.isBefore(slot.getEnd())) {
                GoalTaskDto t = sortedTasks.get(taskIdx);
                int minutes = t.getEstimatedMinutes() == null || t.getEstimatedMinutes() <= 0 ? 30 : t.getEstimatedMinutes();
                boolean isDeep = minutes >= 60;
                if (isDeep && deepCount >= 3 && cursor.toLocalDate().equals(slot.getStart().toLocalDate())) {
                    taskIdx++;
                    continue;
                }
                long remaining = java.time.Duration.between(cursor, slot.getEnd()).toMinutes();
                if (remaining < 15) {
                    break;
                }
                int useMinutes = (int) Math.min(Math.max(15, minutes), remaining);
                LocalDateTime end = cursor.plusMinutes(useMinutes);
                TaskSchedule s = new TaskSchedule();
                s.setTaskId(t.getId());
                s.setStartTime(cursor);
                s.setEndTime(end);
                out.add(s);
                if (isDeep && useMinutes >= 60) {
                    deepCount++;
                }
                cursor = end;
                taskIdx++;
            }
            if (taskIdx >= sortedTasks.size()) {
                break;
            }
        }
        return out;
    }

    String writeJson(Object obj) {
        return scheduleUtils.writeJson(obj);
    }

    private <T> T readJsonList(String json, TypeReference<T> type) {
        return scheduleUtils.readJsonList(json, type);
    }

    public List<TaskScheduleDto> listTaskSchedules(Long userId, LocalDateTime from, LocalDateTime to) {
        return taskScheduleService.listTaskSchedules(userId, from, to);
    }

    public void updateTaskScheduleStatus(Long scheduleId, Integer status) {
        taskScheduleService.updateTaskScheduleStatus(scheduleId, status);
    }

    public void deleteFutureTaskSchedules(Long userId) {
        taskScheduleService.deleteFutureTaskSchedules(userId);
    }

    public void deleteTaskSchedulesByTaskIds(Long userId, List<Long> taskIds) {
        taskScheduleService.deleteTaskSchedulesByTaskIds(userId, taskIds);
    }

    public void deleteTaskSchedulesByDate(Long userId, String dateStr) {
        taskScheduleService.deleteTaskSchedulesByDate(userId, dateStr);
    }
}
