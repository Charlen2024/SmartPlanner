package com.chao.agent.tool;

import com.chao.agent.service.AgentRagIndexer;
import com.chao.agent.util.AgentUserContext;
import com.chao.agent.util.ChatTextUtils;
import com.chao.common.client.GoalClient;
import com.chao.common.client.PunchClient;
import com.chao.common.client.ResourceClient;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.*;
import com.chao.common.util.WeatherClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class SmartPlannerTools {
    private static final Logger log = LoggerFactory.getLogger(SmartPlannerTools.class);
    private final GoalClient goalClient;
    private final ScheduleClient scheduleClient;
    private final PunchClient punchClient;
    private final ResourceClient resourceClient;
    private final RedissonClient redissonClient;
    private final VectorStore vectorStore;
    private final WeatherClient weatherClient;
    private final AgentRagIndexer agentRagIndexer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SmartPlannerTools(
            GoalClient goalClient,
            ScheduleClient scheduleClient,
            PunchClient punchClient,
            ResourceClient resourceClient,
            RedissonClient redissonClient,
            ObjectProvider<VectorStore> vectorStoreProvider,
            WeatherClient weatherClient,
            AgentRagIndexer agentRagIndexer) {
        this.goalClient = goalClient;
        this.scheduleClient = scheduleClient;
        this.punchClient = punchClient;
        this.resourceClient = resourceClient;
        this.redissonClient = redissonClient;
        this.vectorStore = vectorStoreProvider != null ? vectorStoreProvider.getIfAvailable() : null;
        this.weatherClient = weatherClient;
        this.agentRagIndexer = agentRagIndexer;
    }

    @Tool(description = "查询当前用户的目标列表。返回 JSON 数组，每个元素包含 id、title、description、deadline、status。")
    public List<Map<String, Object>> listGoals() {
        Long userId = requireUserId();
        List<GoalDto> goals = safeList(goalClient.listGoals(userId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (GoalDto g : goals) {
            if (g == null || g.getId() == null) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("id", g.getId());
            m.put("title", g.getTitle());
            m.put("description", g.getDescription());
            m.put("status", g.getStatus());
            m.put("deadline", g.getDeadline());
            out.add(m);
            if (out.size() >= 20) break;
        }
        return out;
    }

    @Tool(description = "查询当前用户的目标级待办任务定义（goal tasks，不含排程时间，仅含任务标题/描述/优先级等）。注意：此工具不返回排程信息！当用户问【今天有什么任务/今天做什么/我的日程】时必须改用 listTaskSchedules。返回 JSON 数组，每个元素包含 id、goalId、title、description、estimatedMinutes、priority、status。")
    public List<Map<String, Object>> listPendingTasks() {
        Long userId = requireUserId();
        List<GoalTaskDto> tasks = safeList(goalClient.getPendingTasks(userId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (GoalTaskDto t : tasks) {
            if (t == null || t.getId() == null) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.getId());
            m.put("goalId", t.getGoalId());
            m.put("title", t.getTitle());
            m.put("description", t.getDescription());
            m.put("estimatedMinutes", t.getEstimatedMinutes());
            m.put("priority", t.getPriority());
            m.put("status", t.getStatus());
            out.add(m);
            if (out.size() >= 30) break;
        }
        return out;
    }

    @Tool(description = "查询某个目标下的任务列表。用于用户问[这个目标有哪些任务]。")
    public List<Map<String, Object>> listGoalTasks(@ToolParam(description = "目标ID") Long goalId) {
        Long userId = requireUserId();
        if (goalId == null) return List.of();
        List<GoalTaskDto> tasks = safeList(goalClient.listTasks(goalId, userId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (GoalTaskDto t : tasks) {
            if (t == null || t.getId() == null) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.getId());
            m.put("goalId", t.getGoalId());
            m.put("title", t.getTitle());
            m.put("description", t.getDescription());
            m.put("estimatedMinutes", t.getEstimatedMinutes());
            m.put("priority", t.getPriority());
            m.put("status", t.getStatus());
            out.add(m);
            if (out.size() >= 50) break;
        }
        return out;
    }

    @Tool(description = "查询当前用户今天的排程（今天有什么任务/日程）。无参数，自动查今天。禁止用此结果编造日程建议。")
    public List<Map<String, Object>> listTodaySchedules() {
        Long userId = requireUserId();
        LocalDate today = LocalDate.now(ChatTextUtils.ZONE_SHANGHAI);
        String from = today + "T00:00:00";
        String to = today + "T23:59:59";
        List<TaskScheduleDto> list = safeList(scheduleClient.listTaskSchedules(userId, from, to));
        List<Map<String, Object>> out = new ArrayList<>();
        for (TaskScheduleDto s : list) {
            if (s == null || s.getTaskId() == null) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId());
            m.put("taskId", s.getTaskId());
            m.put("taskTitle", s.getTaskTitle());
            m.put("startTime", s.getStartTime());
            m.put("endTime", s.getEndTime());
            m.put("status", s.getStatus());
            out.add(m);
            if (out.size() >= 50) break;
        }
        return out;
    }

    @Tool(description = "查询当前用户已排程的任务列表（task_schedule，包含具体开始/结束时间）。当用户询问【今天有什么任务/我的日程/排程/今天做什么】时使用。返回每个任务的具体时间段。")
    public List<Map<String, Object>> listTaskSchedules(
            @ToolParam(description = "起始时间（ISO-8601，如 2026-05-22T00:00:00）", required = false) @Nullable String from,
            @ToolParam(description = "结束时间（ISO-8601，如 2026-05-22T23:59:59）", required = false) @Nullable String to) {
        Long userId = requireUserId();
        boolean fromBlank = from == null || from.isBlank();
        boolean toBlank = to == null || to.isBlank();
        if (fromBlank && toBlank) {
            LocalDate today = LocalDate.now(ChatTextUtils.ZONE_SHANGHAI);
            from = today + "T00:00:00";
            to = today + "T23:59:59";
        }
        List<TaskScheduleDto> list = safeList(scheduleClient.listTaskSchedules(userId, from, to));
        List<Map<String, Object>> out = new ArrayList<>();
        for (TaskScheduleDto s : list) {
            if (s == null || s.getTaskId() == null) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId());
            m.put("taskId", s.getTaskId());
            m.put("taskTitle", s.getTaskTitle());
            m.put("startTime", s.getStartTime());
            m.put("endTime", s.getEndTime());
            m.put("status", s.getStatus());
            out.add(m);
            if (out.size() >= 50) break;
        }
        return out;
    }

    @Tool(description = "查询当前用户的课表（课程安排/上课时间）。返回格式化文本，逐条列出课程。问'今天/明天/周X有什么课'时必须传 date 参数。")
    public String listClasses(
            @ToolParam(description = "星期几（1=周一，2=周二...），可空", required = false) @Nullable Integer dayOfWeek,
            @ToolParam(description = "日期（YYYY-MM-DD），问今天/明天/某天时必须传此参数", required = false) @Nullable String date,
            @ToolParam(description = "第一周周一日期（YYYY-MM-DD），用于计算当前教学周，可空", required = false) @Nullable String firstWeekMonday) {
        Long userId = requireUserId();
        boolean dateBlank = date == null || date.isBlank();
        boolean dowNull = dayOfWeek == null;
        boolean fwmBlank = firstWeekMonday == null || firstWeekMonday.isBlank();
        if (dateBlank && dowNull && fwmBlank) {
            date = LocalDate.now(ChatTextUtils.ZONE_SHANGHAI).toString();
        }
        List<ClassScheduleDto> list = safeList(scheduleClient.listClasses(userId, dayOfWeek, date, firstWeekMonday));
        if (list.isEmpty()) {
            return "课表为空，请先导入课表。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共").append(list.size()).append("门课：\n");
        String[] wd = {"日", "一", "二", "三", "四", "五", "六"};
        int idx = 0;
        for (ClassScheduleDto c : list) {
            if (c == null || c.getId() == null) continue;
            idx++;
            String name = c.getCourseName() != null ? c.getCourseName() : "未命名";
            String start = c.getStartTime() != null ? c.getStartTime().toString() : "";
            String end = c.getEndTime() != null ? c.getEndTime().toString() : "";
            String loc = c.getLocation() != null ? " " + c.getLocation() : "";
            int dow = c.getDayOfWeek() != null ? c.getDayOfWeek() : 0;
            sb.append(idx).append(". ").append(name).append(" 周").append(wd[dow % 7]).append(" ").append(start).append("-").append(end).append(loc).append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "查询当前用户的打卡记录列表。用于查看打卡历史、本周/最近打卡情况、判断任务是否完成。返回 JSON 数组，每个元素包含 id、taskId、taskTitle（任务名）、startedAt、endedAt、createdAt（打卡时间）、durationSeconds、aiAuditResult、aiAuditRemark。不传时间参数时返回最近记录。")
    public List<Map<String, Object>> listPunchRecords(
            @ToolParam(description = "任务ID，可空", required = false) @Nullable Long taskId,
            @ToolParam(description = "起始时间（ISO-8601），可空", required = false) @Nullable String from,
            @ToolParam(description = "结束时间（ISO-8601），可空", required = false) @Nullable String to) {
        Long userId = requireUserId();
        List<PunchRecordDto> list = safeList(punchClient.listRecords(userId, taskId, from, to));

        Map<Long, String> titleMap = new HashMap<>();
        try {
            List<Long> taskIds = list.stream().map(PunchRecordDto::getTaskId).filter(Objects::nonNull).distinct().collect(java.util.stream.Collectors.toList());
            if (!taskIds.isEmpty()) {
                List<GoalTaskDto> tasks = safeList(goalClient.getTasksByIds(taskIds));
                for (GoalTaskDto t : tasks) {
                    if (t != null && t.getId() != null) {
                        titleMap.put(t.getId(), t.getTitle() != null ? t.getTitle() : "未命名任务");
                    }
                }
                List<Long> unresolved = taskIds.stream().filter(id -> !titleMap.containsKey(id)).collect(java.util.stream.Collectors.toList());
                if (!unresolved.isEmpty()) {
                    try {
                        List<TaskScheduleDto> schedules = safeList(scheduleClient.listTaskSchedules(userId, null, null));
                        for (TaskScheduleDto s : schedules) {
                            if (s != null && s.getTaskId() != null && unresolved.contains(s.getTaskId()) && s.getTaskTitle() != null) {
                                titleMap.put(s.getTaskId(), s.getTaskTitle());
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (PunchRecordDto p : list) {
            if (p == null || p.getId() == null) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("id", p.getId());
            m.put("taskId", p.getTaskId());
            String storedTitle = p.getTaskTitle();
            m.put("taskTitle", (storedTitle != null && !storedTitle.isBlank()) ? storedTitle : titleMap.getOrDefault(p.getTaskId(), "未知任务"));
            m.put("startedAt", p.getStartedAt());
            m.put("endedAt", p.getEndedAt());
            m.put("createdAt", p.getCreatedAt());
            m.put("durationSeconds", p.getDurationSeconds());
            m.put("durationText", ChatTextUtils.formatDuration(p.getDurationSeconds()));
            m.put("aiAuditResult", p.getAiAuditResult());
            m.put("aiAuditRemark", p.getAiAuditRemark());
            out.add(m);
            if (out.size() >= 60) break;
        }
        return out;
    }

    @Tool(description = "查询最近 N 天的随笔列表（包含心情 mood）。用于复盘/总结/找出情绪与学习模式。当用户询问【我的随笔/日记/复盘/最近记录了什么】时必须调用此工具。返回 JSON 数组，每个元素包含 id、goalId、createdAt、mood、text。")
    public List<Map<String, Object>> listRecentJournals(
            @ToolParam(description = "最近天数，1-30，可空，默认 7", required = false) @Nullable Integer days,
            @ToolParam(description = "目标ID，可空", required = false) @Nullable Long goalId,
            @ToolParam(description = "最多返回条数 1-80，可空，默认 30", required = false) @Nullable Integer limit) {
        Long userId = requireUserId();
        int d = days == null ? 7 : Math.max(1, Math.min(days, 30));
        int lim = limit == null ? 30 : Math.max(1, Math.min(limit, 80));
        LocalDateTime from = LocalDateTime.now(ChatTextUtils.ZONE_SHANGHAI).minusDays(d);
        log.info("listRecentJournals CALLED userId={}, days={}, goalId={}, from={}", userId, d, goalId, from);

        List<UserJournalDto> list;
        try {
            list = safeList(goalClient.listJournals(userId, goalId));
            log.info("listRecentJournals Feign returned {} journals for userId={}", list.size(), userId);
        } catch (Exception e) {
            log.warn("listRecentJournals Feign call failed for userId={}: {}", userId, e.getMessage());
            return List.of();
        }

        List<UserJournalDto> filtered = new ArrayList<>();
        for (UserJournalDto j : list) {
            if (j == null || j.getId() == null) continue;
            LocalDateTime at = j.getCreatedAt();
            if (at == null || at.isBefore(from)) {
                log.info("listRecentJournals FILTERED OUT id={}, createdAt={}, from={}", j.getId(), at, from);
                continue;
            }
            filtered.add(j);
        }
        filtered.sort((a, b) -> {
            LocalDateTime x = a != null ? a.getCreatedAt() : null;
            LocalDateTime y = b != null ? b.getCreatedAt() : null;
            if (x == null && y == null) return 0;
            if (x == null) return 1;
            if (y == null) return -1;
            return y.compareTo(x);
        });

        List<Map<String, Object>> out = new ArrayList<>();
        for (UserJournalDto j : filtered) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", j.getId());
            m.put("goalId", j.getGoalId());
            m.put("createdAt", j.getCreatedAt());
            m.put("mood", j.getMood());
            m.put("text", ChatTextUtils.safeSnippet(j.getContent()));
            out.add(m);
            if (out.size() >= lim) break;
        }
        log.info("listRecentJournals RESULT userId={}, totalFetched={}, afterFilter={}, returned={}", userId, list.size(), filtered.size(), out.size());
        return out;
    }

    @Tool(description = "混合检索：从用户随笔、打卡记录、课程资源中检索与 query 最相关的内容。优先走向量检索（RedisStack），并在必要时用关键词检索补充。返回 JSON 数组，每个元素包含 type（journal/punch/course）、title、text、url 等。")
    public List<Map<String, Object>> searchPersonalData(
            @ToolParam(description = "检索关键词/问题") String query,
            @ToolParam(description = "返回条数 1-10", required = false) @Nullable Integer topK) {
        Long userId = requireUserId();
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) return List.of();
        int k = topK == null ? 6 : Math.max(1, Math.min(topK, 10));

        List<Map<String, Object>> out = new ArrayList<>();

        log.info("searchPersonalData CALLED userId={}, q={}, topK={}, vectorStore={}", userId, q, k, vectorStore != null ? "present" : "NULL");
        if (vectorStore != null) {
            try {
                agentRagIndexer.ensureUserRagIndexed(userId);
                FilterExpressionBuilder fb = new FilterExpressionBuilder();
                var filter = fb.or(fb.eq("userId", String.valueOf(userId)), fb.eq("type", "course")).build();
                List<Document> docs = vectorStore.similaritySearch(
                        SearchRequest.builder().query(q).topK(k).filterExpression(filter).build());
                if (docs != null) {
                    for (Document d : docs) {
                        if (d == null) continue;
                        Map<String, Object> m = new HashMap<>();
                        Object type = d.getMetadata() != null ? d.getMetadata().get("type") : null;
                        m.put("type", type);
                        m.put("text", ChatTextUtils.safeSnippet(d.getText()));
                        if (d.getMetadata() != null) {
                            m.put("title", d.getMetadata().get("title"));
                            m.put("url", d.getMetadata().get("url"));
                            m.put("platform", d.getMetadata().get("platform"));
                            m.put("topic", d.getMetadata().get("topic"));
                            m.put("goalId", d.getMetadata().get("goalId"));
                            m.put("createdAt", d.getMetadata().get("createdAt"));
                            m.put("mood", d.getMetadata().get("mood"));
                        }
                        out.add(m);
                        if (out.size() >= k) break;
                    }
                }
                log.info("searchPersonalData vectorSearch userId={}, q={}, found={}", userId, q, out.size());
            } catch (Exception e) {
                log.warn("searchPersonalData vectorSearch failed userId={}, q={}: {}", userId, q, e.getMessage());
            }
        } else {
            log.info("searchPersonalData vectorStore is null, skipping to keywordFallback userId={}", userId);
        }

        if (out.size() < k) {
            int remain = k - out.size();
            List<Map<String, Object>> fallback = keywordFallback(userId, q, remain);
            out.addAll(fallback);
        }
        return out;
    }

    @Tool(description = "查询指定城市的实时天气。返回温度、天气状况、体感温度、湿度、风速等信息。若不指定城市，会自动使用用户在仪表盘选择的城市。可查询中文城市名（如：北京、上海、广州）或英文城市名。")
    public Map<String, Object> getWeather(
            @ToolParam(description = "城市名称，中文或英文，例如：深圳、北京、Shanghai。留空则使用用户保存的城市", required = false) String location) {
        WeatherData wd;
        String displayLocation;

        if (location != null && !location.isBlank()) {
            // User specified a city — use it directly
            displayLocation = location.trim();
            wd = weatherClient.fetch(displayLocation);
        } else {
            // Try Redis-cached coordinates first, fall back to name
            WeatherLocInfo wli = getUserSavedLocationInfo();
            if (wli.hasCoords()) {
                wd = weatherClient.fetch(wli.lat, wli.lon);
                displayLocation = !wli.name.isBlank() ? wli.name : wd.getLocation();
            } else {
                String loc = !wli.name.isBlank() ? wli.name : "Shenzhen";
                wd = weatherClient.fetch(loc);
                displayLocation = loc;
            }
        }

        if (wd == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "天气服务暂不可用，请稍后重试");
            return err;
        }
        Map<String, Object> out = new HashMap<>();
        out.put("location", displayLocation);
        out.put("temperature_C", wd.getTemperature());
        out.put("feelsLike_C", wd.getFeelsLike());
        out.put("humidity", wd.getHumidity());
        out.put("windSpeed_kmph", wd.getWindspeed());
        out.put("windDirection", wd.getWindDirection());
        out.put("visibility_km", wd.getVisibility());
        out.put("pressure", wd.getPressure());
        out.put("weather", wd.getWeatherDescCn());
        out.put("weatherEn", wd.getWeatherDesc());
        out.put("maxTemp_C", wd.getMaxTemp());
        out.put("minTemp_C", wd.getMinTemp());
        out.put("sunHour", wd.getSunHour());
        if (wd.getWeatherDescCn() == null && wd.getTemperature() == null) {
            out.put("error", "天气服务暂不可用");
        }
        return out;
    }

    // --- 以下写操作方法暂未加 @Tool，保留以备后续启用 ---

    // @Tool(description = "创建一条随笔/日记记录")
    public String createJournal(
            @ToolParam(description = "随笔内容") String content,
            @ToolParam(description = "关联目标ID，可空", required = false) @Nullable Long goalId,
            @ToolParam(description = "心情标签，可空", required = false) @Nullable String mood) {
        Long userId = requireUserId();
        String c = content == null ? "" : content.trim();
        if (c.isBlank()) return "内容为空";
        Result<String> res = goalClient.createJournal(userId, goalId, c, mood);
        if (res != null && res.getCode() == 200) {
            try {
                indexSingleJournal(userId, goalId, c);
            } catch (Exception ignored) {
            }
            return "已记录";
        }
        return "记录失败";
    }

    // @Tool(description = "为某个目标添加一个学习任务")
    public String addTask(
            @ToolParam(description = "目标ID") Long goalId,
            @ToolParam(description = "任务标题") String title,
            @ToolParam(description = "任务描述，可空", required = false) @Nullable String description,
            @ToolParam(description = "优先级 0-2，可空", required = false) @Nullable Integer priority,
            @ToolParam(description = "预计分钟数，可空", required = false) @Nullable Integer estimatedMinutes) {
        Long userId = requireUserId();
        if (goalId == null) return "缺少 goalId";
        String t = title == null ? "" : title.trim();
        if (t.isBlank()) return "任务标题不能为空";
        Result<GoalTaskDto> res = goalClient.createTask(goalId, userId, null, t, description, priority != null ? priority : 0, estimatedMinutes, null);
        if (res != null && res.getCode() == 200 && res.getData() != null && res.getData().getId() != null) {
            return "已添加 taskId=" + res.getData().getId();
        }
        if (res != null && res.getMessage() != null && !res.getMessage().isBlank()) {
            return res.getMessage();
        }
        return "添加失败";
    }

    // @Tool(description = "触发日计划排程 job")
    public Map<String, Object> startDailyPlanJob(
            @ToolParam(description = "日期（YYYY-MM-DD）") String date,
            @ToolParam(description = "模式：replace 或 merge", required = false) @Nullable String mode,
            @ToolParam(description = "目标ID，可空", required = false) @Nullable Long goalId,
            @ToolParam(description = "任务ID列表，可空", required = false) @Nullable List<Long> taskIds) {
        Long userId = requireUserId();
        DailyPlanJobStartRequest req = new DailyPlanJobStartRequest();
        req.setDate(date == null || date.isBlank() ? null : LocalDate.parse(date.trim()));
        req.setMode(mode);
        req.setGoalId(goalId);
        req.setTaskIds(taskIds);
        Result<DailyPlanJobStartResponse> res = scheduleClient.startDailyPlanJob(userId, req);
        String jobId = res != null && res.getData() != null ? res.getData().getJobId() : null;
        Map<String, Object> out = new HashMap<>();
        out.put("jobId", jobId);
        return out;
    }

    // @Tool(description = "查询日计划排程 job 状态")
    public Map<String, Object> getDailyPlanJobStatus(@ToolParam(description = "jobId") String jobId) {
        Long userId = requireUserId();
        Result<DailyPlanJobStatusResponse> res = scheduleClient.getDailyPlanJobStatus(userId, jobId);
        Map<String, Object> out = new HashMap<>();
        if (res != null && res.getData() != null) {
            out.put("status", res.getData().getStatus());
            out.put("stage", res.getData().getStage());
            out.put("progress", res.getData().getProgress());
            out.put("message", res.getData().getMessage());
            out.put("error", res.getData().getError());
        }
        return out;
    }

    private record WeatherLocInfo(Double lat, Double lon, String name) {
        boolean hasCoords() { return lat != null && lon != null; }
    }

    private WeatherLocInfo getUserSavedLocationInfo() {
        try {
            Long userId = AgentUserContext.get();
            if (userId == null) return new WeatherLocInfo(null, null, "");
            if (redissonClient != null) {
                String raw = String.valueOf(redissonClient.getBucket("sp:weather:loc:" + userId).get());
                if (raw == null || "null".equals(raw)) return new WeatherLocInfo(null, null, "");
                if (raw.startsWith("{")) {
                    Map m = objectMapper.readValue(raw, Map.class);
                    Double lat = toDouble(m.get("lat"));
                    Double lon = toDouble(m.get("lon"));
                    String name = String.valueOf(m.getOrDefault("name", ""));
                    return new WeatherLocInfo(lat, lon, "null".equals(name) ? "" : name.trim());
                }
                // Legacy: plain city name
                return new WeatherLocInfo(null, null, raw.trim());
            }
        } catch (Exception ignored) {}
        return new WeatherLocInfo(null, null, "");
    }

    private Double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private String getUserSavedLocation() {
        return getUserSavedLocationInfo().name;
    }

    private Long requireUserId() {
        Long userId = AgentUserContext.get();
        if (userId == null) throw new IllegalStateException("AgentUserContext.userId not set — ensure AgentChatService sets it before agent invocation");
        return userId;
    }

    private void indexSingleJournal(Long userId, Long goalId, String content) {
        if (vectorStore == null) return;
        if (userId == null) return;
        String text = content == null ? "" : content.trim();
        if (text.isBlank()) return;
        Map<String, Object> meta = new HashMap<>();
        meta.put("type", "journal");
        meta.put("userId", userId);
        meta.put("goalId", goalId);
        meta.put("title", "随笔");
        vectorStore.add(List.of(new Document("journal:" + userId + ":" + System.currentTimeMillis(), text, meta)));
    }

    private static <T> List<T> safeList(Result<List<T>> res) {
        if (res == null || res.getCode() != 200) return List.of();
        List<T> data = res.getData();
        return data != null ? data : List.of();
    }

    private List<Map<String, Object>> keywordFallback(Long userId, String q, int limit) {
        if (limit <= 0) return List.of();
        log.info("keywordFallback CALLED userId={}, q={}, limit={}", userId, q, limit);
        List<Map<String, Object>> out = new ArrayList<>();

        // Journal fallback: first try content match, then list recent if the query looks like a listing request
        boolean journalListing = q.length() <= 30 && (q.contains("随笔") || q.contains("日记") || q.contains("复盘") || q.contains("journal"));
        try {
            List<UserJournalDto> journals = safeList(goalClient.listJournals(userId, null));
            // Sort by createdAt desc for listing
            if (journalListing) {
                journals = new ArrayList<>(journals);
                journals.sort((a, b) -> {
                    java.time.LocalDateTime x = a != null ? a.getCreatedAt() : null;
                    java.time.LocalDateTime y = b != null ? b.getCreatedAt() : null;
                    if (x == null && y == null) return 0;
                    if (x == null) return 1;
                    if (y == null) return -1;
                    return y.compareTo(x);
                });
            }
            for (UserJournalDto j : journals) {
                if (j == null) continue;
                String text = j.getContent();
                boolean match = text != null && text.toLowerCase().contains(q.toLowerCase());
                if (match || journalListing) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("type", "journal");
                    m.put("goalId", j.getGoalId());
                    m.put("createdAt", j.getCreatedAt());
                    m.put("mood", j.getMood());
                    m.put("text", ChatTextUtils.safeSnippet(text));
                    out.add(m);
                    if (out.size() >= limit) return out;
                }
            }
            if (journalListing) {
                log.info("keywordFallback journalListing userId={}, totalJournals={}, returned={}", userId, journals.size(), Math.min(out.size(), limit));
            }
        } catch (Exception e) {
            log.warn("keywordFallback journal fetch failed userId={}: {}", userId, e.getMessage());
        }

        Set<String> userGoalKeywords = Collections.emptySet();
        try {
            List<GoalDto> goals = safeList(goalClient.listGoals(userId));
            userGoalKeywords = new HashSet<>();
            for (GoalDto g : goals) {
                if (g != null && g.getTitle() != null) {
                    for (String word : g.getTitle().split("[\\s，,、]+")) {
                        String w = word.trim();
                        if (w.length() >= 2) userGoalKeywords.add(w);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        try {
            List<SearchResourceItem> list = safeList(resourceClient.searchOnlineCourses(q));
            for (SearchResourceItem r : list) {
                if (r == null) continue;
                Map<String, Object> m = new HashMap<>();
                m.put("type", "course");
                m.put("title", r.getTitle());
                m.put("platform", r.getPlatform());
                m.put("url", r.getUrl());
                m.put("text", ChatTextUtils.safeSnippet(r.getSummary()));
                String title = r.getTitle() != null ? r.getTitle() : "";
                String summary = r.getSummary() != null ? r.getSummary() : "";
                for (String kw : userGoalKeywords) {
                    if (title.contains(kw) || summary.contains(kw)) {
                        m.put("matchesYourGoal", true);
                        m.put("matchingGoalKeyword", kw);
                        break;
                    }
                }
                out.add(m);
                if (out.size() >= limit) return out;
            }
        } catch (Exception ignored) {
        }

        return out;
    }
}
