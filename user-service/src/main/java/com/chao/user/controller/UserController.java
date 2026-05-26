package com.chao.user.controller;

import com.chao.common.client.GoalClient;
import com.chao.common.client.PunchClient;
import com.chao.common.client.ResourceClient;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.*;
import com.chao.user.dto.DashboardDto;
import com.chao.user.service.UserService;
import com.chao.user.service.TaskAdviceAiService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final GoalClient goalClient;
    private final ScheduleClient scheduleClient;
    private final PunchClient punchClient;
    private final ResourceClient resourceClient;
    private final com.chao.user.service.AppUserService appUserService;
    private final TaskAdviceAiService taskAdviceAiService;
    private final RedissonClient redissonClient;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectMapper objectMapper;

    private static final String TASK_RESOURCES_KEY_PREFIX = "sp:task:resources:v2:";
    private static final String COURSES_INDEXED_KEY = "sp:rag:indexed:courses";
    private static final String TASK_ADVICE_KEY_PREFIX = "sp:task:advice:v1:";

    @GetMapping("/dashboard")
    public Result<DashboardDto> dashboard(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String topic) {
        return Result.success(userService.getDashboard(resolveUserId(jwt, headerUserId, userId), date, topic));
    }

    @PostMapping("/goals/ai")
    public Result<GoalDto> createGoalByAi(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody String goalDescription) {
        Long uid = resolveUserId(jwt, headerUserId, userId);
        com.chao.user.entity.AppUser u = appUserService.getById(uid);
        if (u != null && Boolean.FALSE.equals(u.getScheduleImported())) {
            return Result.fail(400, "请先导入课表后再生成学习计划");
        }
        return goalClient.createGoalByAi(uid, goalDescription);
    }

    @PostMapping("/goals")
    public Result<GoalDto> createGoal(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String deadline) {
        return goalClient.createGoalRecord(resolveUserId(jwt, headerUserId, userId), title, description, deadline);
    }

    @GetMapping("/goals")
    public Result<List<GoalDto>> listGoals(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return goalClient.listGoals(resolveUserId(jwt, headerUserId, userId));
    }

    @GetMapping("/tasks/pending")
    public Result<List<GoalTaskDto>> pendingTasks(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return goalClient.getPendingTasks(resolveUserId(jwt, headerUserId, userId));
    }

    @PostMapping("/tasks/by-ids")
    public Result<List<GoalTaskDto>> tasksByIds(@RequestBody List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Result.success(List.of());
        }
        return goalClient.getTasksByIds(taskIds);
    }

    
    @PostMapping("/tasks/advice")
    public Result<java.util.Map<Long, String>> taskAdvice(@RequestBody List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Result.success(java.util.Map.of());
        }
        // Try Redis cache first
        Map<Long, String> cached = new HashMap<>();
        List<Long> uncached = new ArrayList<>();
        if (redissonClient != null) {
            for (Long tid : taskIds) {
                if (tid == null || tid <= 0) continue;
                try {
                    String key = TASK_ADVICE_KEY_PREFIX + tid;
                    String val = (String) redissonClient.getBucket(key).get();
                    if (val != null && !val.isBlank()) {
                        cached.put(tid, val);
                    } else {
                        uncached.add(tid);
                    }
                } catch (Exception ignored) {
                    uncached.add(tid);
                }
            }
        } else {
            uncached.addAll(taskIds);
        }

        // Fetch advice from AI for uncached tasks
        if (!uncached.isEmpty()) {
            List<GoalTaskDto> tasks = goalClient.getTasksByIds(uncached).getData();
            if (tasks != null && !tasks.isEmpty()) {
                Map<Long, String> fresh = taskAdviceAiService.advise(tasks);
                if (fresh != null) {
                    if (redissonClient != null) {
                        for (Map.Entry<Long, String> e : fresh.entrySet()) {
                            try {
                                String key = TASK_ADVICE_KEY_PREFIX + e.getKey();
                                redissonClient.getBucket(key).set(e.getValue());
                                redissonClient.getBucket(key).expire(Duration.ofDays(7));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    cached.putAll(fresh);
                }
            }
        }
        return Result.success(cached);
    }

    @GetMapping("/assistant/advice")
    public Result<String> assistantAdvice(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam String date) {
        Long uid = resolveUserId(jwt, headerUserId, userId);
        String d = date != null ? date.trim() : "";
        if (d.isBlank()) {
            d = LocalDate.now().toString();
        }
        String from = d + "T00:00:00";
        String to = d + "T23:59:59";

        List<TaskScheduleDto> schedules = scheduleClient.listTaskSchedules(uid, from, to).getData();
        schedules = schedules != null ? schedules : List.of();
        if (schedules.isEmpty()) {
            return Result.success("今天暂无排程任务");
        }

        List<Long> taskIds = schedules.stream()
                .map(TaskScheduleDto::getTaskId)
                .filter(x -> x != null && x > 0)
                .distinct()
                .toList();

        List<GoalTaskDto> tasks = goalClient.getTasksByIds(taskIds).getData();
        tasks = tasks != null ? tasks : List.of();
        Map<Long, GoalTaskDto> taskMap = new HashMap<>();
        for (GoalTaskDto t : tasks) {
            if (t != null && t.getId() != null) taskMap.put(t.getId(), t);
        }

        List<TaskAdviceAiService.ScheduleAdviceItem> items = new ArrayList<>();
        for (TaskScheduleDto s : schedules) {
            if (s == null || s.getTaskId() == null) continue;
            GoalTaskDto t = taskMap.get(s.getTaskId());
            TaskAdviceAiService.ScheduleAdviceItem it = new TaskAdviceAiService.ScheduleAdviceItem();
            it.taskId = s.getTaskId();
            it.title = safeText(s.getTaskTitle() != null ? s.getTaskTitle() : (t != null ? t.getTitle() : null));
            it.description = safeText(t != null ? t.getDescription() : null);
            it.startTime = s.getStartTime() != null ? s.getStartTime().toString() : null;
            it.endTime = s.getEndTime() != null ? s.getEndTime().toString() : null;
            it.timeBudgetMinutes = budgetMinutes(s.getStartTime(), s.getEndTime());
            items.add(it);
        }

        String moodHint = buildMoodHint(uid);
        TaskAdviceAiService.ScheduleAdviceResponse advice = taskAdviceAiService.adviseSchedules(items, moodHint);
        String header = safeText(advice != null ? advice.header : null);
        if (header.isBlank()) {
            header = "今天的小建议：先把每个任务的第一步做完；每项完成到“能复述/能交付”就算达标。";
        }

        Map<Long, TaskAdviceAiService.TaskAdvice> perTask = advice != null && advice.items != null ? advice.items : Map.of();
        StringBuilder out = new StringBuilder();
        out.append(header);

        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        int i = 0;
        for (TaskScheduleDto s : schedules) {
            if (s == null || s.getTaskId() == null) continue;
            i++;
            Long tid = s.getTaskId();
            GoalTaskDto t = taskMap.get(tid);
            String title = safeText(s.getTaskTitle() != null ? s.getTaskTitle() : (t != null ? t.getTitle() : ("任务 " + tid)));
            String range = fmtHm(hm, s.getStartTime()) + "-" + fmtHm(hm, s.getEndTime());

            out.append("\n\n").append(i).append(") ").append(title).append("（").append(range).append("）");

            TaskAdviceAiService.TaskAdvice a = perTask.get(tid);
            String start = safeText(a != null ? a.start : null);
            String done = safeText(a != null ? a.done : null);
            if (start.isBlank()) start = "先用 10 分钟把第一步做完（只做最小可推进的动作）。";
            if (done.isBlank()) done = "在这个时间段内完成一个可验收产出（笔记/小测/代码/总结）。";

            out.append("\n开始：").append(start);
            out.append("\n完成：").append(done);

            List<CourseResourceDto> rs = recommendCourseResources(uid, t, 2);
            if (rs != null && !rs.isEmpty()) {
                String joined = rs.stream()
                        .map(r -> {
                            if (r == null) return null;
                            String name = safeText(r.getTitle());
                            if (name.isBlank()) name = "课程资源";
                            String platform = safeText(r.getPlatform());
                            return platform.isBlank() ? name : platform + "：" + name;
                        })
                        .filter(x -> x != null && !x.isBlank())
                        .limit(2)
                        .collect(Collectors.joining("；"));
                if (!joined.isBlank()) {
                    out.append("\n资料：").append(joined);
                }
            }
        }

        return Result.success(out.toString());
    }

    static class TaskResourcesRequest {
        private List<Long> taskIds;
        private Integer topK;
        private Boolean refresh;

        public List<Long> getTaskIds() {
            return taskIds;
        }

        public void setTaskIds(List<Long> taskIds) {
            this.taskIds = taskIds;
        }

        public Integer getTopK() {
            return topK;
        }

        public void setTopK(Integer topK) {
            this.topK = topK;
        }

        public Boolean getRefresh() {
            return refresh;
        }

        public void setRefresh(Boolean refresh) {
            this.refresh = refresh;
        }
    }

    private String safeText(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", " ").trim();
    }

    private Integer budgetMinutes(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) return null;
        try {
            long m = Duration.between(start, end).toMinutes();
            if (m <= 0) return null;
            return (int) Math.min(480, m);
        } catch (Exception e) {
            return null;
        }
    }

    private String fmtHm(DateTimeFormatter hm, LocalDateTime t) {
        if (t == null) return "--:--";
        try {
            return hm.format(t);
        } catch (Exception e) {
            String s = t.toString();
            return s.length() >= 16 ? s.substring(11, 16) : s;
        }
    }

    private String buildMoodHint(Long userId) {
        if (userId == null) return "";
        try {
            Result<List<UserJournalDto>> jr = goalClient.listJournals(userId, null);
            List<UserJournalDto> list = jr != null ? jr.getData() : List.of();
            list = list != null ? list : List.of();
            if (list.isEmpty()) return "";

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime from = now.minusDays(7);
            int pos = 0;
            int neg = 0;
            int neu = 0;
            String latestMood = "";
            LocalDateTime latestAt = null;

            for (UserJournalDto j : list) {
                if (j == null) continue;
                String mood = safeText(j.getMood());
                LocalDateTime at = j.getCreatedAt();
                if (!mood.isBlank() && at != null && (latestAt == null || at.isAfter(latestAt))) {
                    latestAt = at;
                    latestMood = mood;
                }
                if (at == null || at.isBefore(from)) continue;
                if (mood.isBlank()) continue;
                int c = moodCategory(mood);
                if (c > 0) pos++;
                else if (c < 0) neg++;
                else neu++;
            }

            if (latestMood.isBlank() && pos == 0 && neg == 0) return "";

            String trend;
            if (neg >= 3 && neg > pos) trend = "最近几天心情偏低或压力偏大";
            else if (pos >= 3 && pos > neg) trend = "最近几天整体状态不错";
            else if (neg >= 2 && pos >= 2) trend = "最近几天状态有波动";
            else trend = "";

            StringBuilder sb = new StringBuilder();
            if (!latestMood.isBlank()) {
                sb.append("最近一次心情：").append(latestMood);
            }
            if (!trend.isBlank()) {
                if (sb.length() > 0) sb.append("；");
                sb.append(trend);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private int moodCategory(String mood) {
        String m = mood == null ? "" : mood;
        if (m.isBlank()) return 0;
        String x = m.toLowerCase();
        if (x.contains("开心") || x.contains("高兴") || x.contains("兴奋") || x.contains("满意") || x.contains("充实") || x.contains("自信") || x.contains("轻松")) {
            return 1;
        }
        if (x.contains("焦虑") || x.contains("难过") || x.contains("沮丧") || x.contains("低落") || x.contains("崩溃") || x.contains("烦") || x.contains("压力") || x.contains("紧张") || x.contains("疲惫") || x.contains("累")) {
            return -1;
        }
        return 0;
    }

    @PostMapping("/tasks/resources")
    public Result<Map<Long, List<CourseResourceDto>>> taskResources(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody(required = false) TaskResourcesRequest request) {
        Long uid = resolveUserId(jwt, headerUserId, userId);
        List<Long> ids = request != null ? request.getTaskIds() : null;
        ids = ids != null ? ids.stream().filter(x -> x != null && x > 0).distinct().collect(Collectors.toList()) : List.of();
        if (ids.isEmpty()) {
            return Result.success(Map.of());
        }
        int topK = request != null && request.getTopK() != null ? request.getTopK() : 3;
        topK = Math.max(1, Math.min(topK, 6));
        boolean refresh = request != null && Boolean.TRUE.equals(request.getRefresh());

        Result<List<GoalTaskDto>> taskRes = goalClient.getTasksByIds(ids);
        List<GoalTaskDto> tasks = taskRes != null ? taskRes.getData() : List.of();
        Map<Long, GoalTaskDto> taskById = new HashMap<>();
        if (tasks != null) {
            for (GoalTaskDto t : tasks) {
                if (t != null && t.getId() != null) {
                    taskById.put(t.getId(), t);
                }
            }
        }

        Map<Long, List<CourseResourceDto>> out = new LinkedHashMap<>();
        for (Long taskId : ids) {
            GoalTaskDto t = taskById.get(taskId);
            List<CourseResourceDto> list = getOrBuildTaskResources(uid, taskId, t, topK, refresh);
            out.put(taskId, list != null ? list : List.of());
        }
        return Result.success(out);
    }

    private List<CourseResourceDto> getOrBuildTaskResources(Long userId, Long taskId, GoalTaskDto task, int topK, boolean refresh) {
        if (taskId == null || taskId <= 0) return List.of();
        if (redissonClient == null) return List.of();

        String key = TASK_RESOURCES_KEY_PREFIX + taskId;
        RBucket<String> bucket = redissonClient.getBucket(key);
        if (!refresh) {
            String cached = bucket.get();
            if (cached != null && !cached.isBlank()) {
                try {
                    CourseResourceDto[] arr = objectMapper.readValue(cached, CourseResourceDto[].class);
                    if (arr != null && arr.length > 0) {
                        return List.of(arr);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        List<CourseResourceDto> rec = recommendCourseResources(userId, task, topK);
        try {
            String json = objectMapper.writeValueAsString(rec != null ? rec : List.of());
            bucket.set(json);
            bucket.expire(Duration.ofDays(14));
        } catch (Exception ignored) {
        }
        return rec != null ? rec : List.of();
    }

    private List<CourseResourceDto> recommendCourseResources(Long userId, GoalTaskDto task, int topK) {
        String title = task != null && task.getTitle() != null ? task.getTitle().trim() : "";
        String desc = task != null && task.getDescription() != null ? task.getDescription().trim() : "";
        String query = title;
        if (!desc.isBlank() && !desc.equalsIgnoreCase(title)) {
            query = title + " " + desc;
        }
        query = query.trim();
        if (query.isBlank()) return List.of();

        VectorStore vectorStore = vectorStoreProvider != null ? vectorStoreProvider.getIfAvailable() : null;
        List<CourseResourceDto> out = new ArrayList<>();

        if (vectorStore != null && ensureCoursesIndexed(vectorStore)) {
            try {
                FilterExpressionBuilder fb = new FilterExpressionBuilder();
                var expr = fb.eq("type", "course").build();
                List<Document> docs = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(query)
                                .topK(topK * 2)
                                .filterExpression(expr)
                                .build()
                );
                if (docs != null) {
                    Set<String> seen = new java.util.HashSet<>();
                    for (Document d : docs) {
                        if (d == null) continue;
                        Map<String, Object> m = d.getMetadata() != null ? d.getMetadata() : Map.of();
                        String t = m.get("title") != null ? String.valueOf(m.get("title")) : "";
                        String url = m.get("url") != null ? String.valueOf(m.get("url")) : "";
                        String platform = m.get("platform") != null ? String.valueOf(m.get("platform")) : "";
                        String topic = m.get("topic") != null ? String.valueOf(m.get("topic")) : title;
                        String sig = (url.isBlank() ? t : url).trim();
                        if (sig.isBlank() || seen.contains(sig)) continue;
                        seen.add(sig);

                        CourseResourceDto dto = new CourseResourceDto();
                        dto.setTopic(topic);
                        dto.setTitle(t);
                        dto.setSourceUrl(url);
                        dto.setPlatform(platform);
                        dto.setContentSummary(safeSnippet(d.getText()));
                        out.add(dto);
                        if (out.size() >= topK) break;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (out.size() < topK) {
            try {
                Result<ResourceClient.ResourceAdviceResponse> r = resourceClient.searchOnlineCoursesWithAdvice(title);
                ResourceClient.ResourceAdviceResponse body = r != null ? r.getData() : null;
                List<ResourceClient.CourseResource> list = body != null && body.getResources() != null ? body.getResources() : List.of();
                if (list != null) {
                    Set<String> seenUrl = out.stream()
                            .map(CourseResourceDto::getSourceUrl)
                            .filter(s -> s != null && !s.isBlank())
                            .collect(Collectors.toSet());
                    for (ResourceClient.CourseResource x : list) {
                        if (x == null) continue;
                        String url = x.getUrl() != null ? x.getUrl().trim() : "";
                        if (!url.isBlank() && seenUrl.contains(url)) continue;
                        if (!url.isBlank()) seenUrl.add(url);
                        CourseResourceDto dto = new CourseResourceDto();
                        dto.setTopic(title);
                        dto.setTitle(x.getTitle());
                        dto.setPlatform(x.getPlatform());
                        dto.setSourceUrl(url);
                        dto.setContentSummary(x.getSummary());
                        out.add(dto);
                        if (out.size() >= topK) break;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return out.size() > topK ? out.subList(0, topK) : out;
    }

    private boolean ensureCoursesIndexed(VectorStore vectorStore) {
        try {
            RBucket<String> b = redissonClient.getBucket(COURSES_INDEXED_KEY);
            if ("1".equals(b.get())) return true;

            Result<List<CourseResourceDto>> rr = resourceClient.listResources(null);
            List<CourseResourceDto> resources = rr != null ? rr.getData() : List.of();
            if (resources == null || resources.isEmpty()) {
                b.set("0");
                b.expire(Duration.ofHours(6));
                return false;
            }

            List<Document> docs = new ArrayList<>();
            int limit = 800;
            for (CourseResourceDto r : resources) {
                if (r == null) continue;
                String text = (r.getTitle() == null ? "" : r.getTitle()) + "\n" + (r.getContentSummary() == null ? "" : r.getContentSummary());
                text = text.trim();
                if (text.isBlank()) continue;
                Map<String, Object> meta = new HashMap<>();
                meta.put("type", "course");
                meta.put("resourceId", r.getId());
                meta.put("title", r.getTitle());
                meta.put("platform", r.getPlatform());
                meta.put("url", r.getSourceUrl());
                meta.put("topic", r.getTopic());
                String id = r.getId() != null ? "course:" + r.getId() : "course:" + docs.size();
                docs.add(new Document(id, text, meta));
                if (docs.size() >= limit) break;
            }
            if (!docs.isEmpty()) {
                addDocsInBatches(vectorStore, docs, 20);
            }
            if (docs.isEmpty()) {
                b.set("0");
                b.expire(Duration.ofHours(6));
                return false;
            }
            b.set("1");
            b.expire(Duration.ofDays(7));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void addDocsInBatches(VectorStore vectorStore, List<Document> docs, int batchSize) {
        if (vectorStore == null || docs == null || docs.isEmpty()) return;
        int size = Math.max(1, Math.min(batchSize, 25));
        for (int i = 0; i < docs.size(); i += size) {
            List<Document> part = docs.subList(i, Math.min(docs.size(), i + size));
            vectorStore.add(part);
        }
    }

    private String safeSnippet(String s) {
        if (s == null) return "";
        String x = s.replace("\n", " ").replace("\r", " ").trim();
        if (x.length() <= 160) return x;
        return x.substring(0, 160);
    }

    @GetMapping("/journals")
    public Result<List<UserJournalDto>> journals(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long goalId) {
        return goalClient.listJournals(resolveUserId(jwt, headerUserId, userId), goalId);
    }

    @PostMapping("/journals")
    public Result<String> createJournal(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long goalId,
            @RequestParam String content,
            @RequestParam(required = false) String mood) {
        return goalClient.createJournal(resolveUserId(jwt, headerUserId, userId), goalId, content, mood);
    }

    @DeleteMapping("/journals/{journalId}")
    public Result<String> deleteJournal(
            @PathVariable Long journalId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return goalClient.deleteJournal(resolveUserId(jwt, headerUserId, userId), journalId);
    }

    @GetMapping("/schedule/free-time")
    public Result<List<ScheduleClient.TimeSlot>> freeTime(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam String date) {
        return scheduleClient.getFreeTimeSlots(resolveUserId(jwt, headerUserId, userId), date);
    }

    @PostMapping("/schedule/auto")
    public Result<String> autoSchedule(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return scheduleClient.autoSchedule(resolveUserId(jwt, headerUserId, userId));
    }

    @PostMapping("/schedule/plan-candidates")
    public Result<PlanCandidateDto> generatePlanCandidate(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody GeneratePlanCandidateRequest request) {
        return scheduleClient.generatePlanCandidate(resolveUserId(jwt, headerUserId, userId), request);
    }

    @PostMapping("/schedule/plan-candidates/{candidateId}/decision")
    public Result<String> decidePlanCandidate(
            @PathVariable Long candidateId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam Boolean accept,
            @RequestParam(required = false) Boolean useSuggestedSlots) {
        return scheduleClient.decidePlanCandidate(candidateId, resolveUserId(jwt, headerUserId, userId), accept, useSuggestedSlots);
    }

    @GetMapping("/schedule/plan-candidates")
    public Result<List<PlanCandidateDto>> listPlanCandidates(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String date) {
        return scheduleClient.listPlanCandidates(resolveUserId(jwt, headerUserId, userId), date);
    }

    @PostMapping("/schedule/daily-plan/commit")
    public Result<DailyPlanCommitResponse> commitDailyPlan(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody DailyPlanCommitRequest request) {
        return scheduleClient.commitDailyPlan(resolveUserId(jwt, headerUserId, userId), request);
    }

    @PostMapping("/schedule/daily-plan/jobs")
    public Result<DailyPlanJobStartResponse> startDailyPlanJob(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody DailyPlanJobStartRequest request) {
        return scheduleClient.startDailyPlanJob(resolveUserId(jwt, headerUserId, userId), request);
    }

    @GetMapping("/schedule/daily-plan/jobs/{jobId}")
    public Result<DailyPlanJobStatusResponse> getDailyPlanJobStatus(
            @PathVariable String jobId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return scheduleClient.getDailyPlanJobStatus(resolveUserId(jwt, headerUserId, userId), jobId);
    }

    @PostMapping("/schedule/import")
    public Result<ScheduleImportResultDto> importSchedule(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam MultipartFile file) {
        Long uid = resolveUserId(jwt, headerUserId, userId);
        Result<ScheduleImportResultDto> r = scheduleClient.importSchedule(uid, file);
        if (r != null && r.getCode() == 200) {
            appUserService.markScheduleImported(uid);
        }
        return r;
    }

    @PostMapping("/punch/submit")
    public Result<String> submitPunch(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam Long taskId,
            @RequestParam Integer type,
            @RequestParam(required = false) Integer durationSeconds,
            @RequestParam(required = false) Long startedAtMs,
            @RequestParam(required = false) Long endedAtMs,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) MultipartFile evidence) {
        return punchClient.submitPunch(resolveUserId(jwt, headerUserId, userId), taskId, type, durationSeconds, startedAtMs, endedAtMs, location, evidence);
    }

    @GetMapping("/resources/search")
    public Result<List<ResourceClient.CourseResource>> searchResources(@RequestParam String topic) {
        return resourceClient.searchOnlineCourses(topic);
    }

    @GetMapping("/resources/search/advice")
    public Result<ResourceClient.ResourceAdviceResponse> searchResourcesWithAdvice(@RequestParam String topic) {
        return resourceClient.searchOnlineCoursesWithAdvice(topic);
    }

    @PostMapping("/resources/search/advice/jobs")
    public Result<ResourceAdviceJobStartResponse> startResourceAdviceJob(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody ResourceAdviceJobStartRequest request) {
        return resourceClient.startResourceAdviceJob(resolveUserId(jwt, headerUserId, userId), request);
    }

    @GetMapping("/resources/search/advice/jobs/{jobId}")
    public Result<ResourceAdviceJobStatusResponse> getResourceAdviceJobStatus(
            @PathVariable String jobId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return resourceClient.getResourceAdviceJobStatus(resolveUserId(jwt, headerUserId, userId), jobId);
    }

    @PostMapping("/resources")
    public Result<CourseResourceDto> createResource(
            @RequestParam String topic,
            @RequestParam String title,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false, name = "url") String url,
            @RequestParam(required = false, name = "summary") String summary) {
        return resourceClient.createResource(topic, title, platform, url, summary);
    }

    @GetMapping("/resources")
    public Result<List<CourseResourceDto>> listResources(@RequestParam(required = false) String topic) {
        return resourceClient.listResources(topic);
    }

    @GetMapping("/resources/{id}")
    public Result<CourseResourceDto> getResource(@PathVariable Long id) {
        return resourceClient.getResource(id);
    }

    @DeleteMapping("/resources/{id}")
    public Result<String> deleteResource(@PathVariable Long id) {
        return resourceClient.deleteResource(id);
    }

    @GetMapping("/schedule/task-schedules")
    public Result<List<TaskScheduleDto>> listTaskSchedules(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Long uid = resolveUserId(jwt, headerUserId, userId);
        return scheduleClient.listTaskSchedules(uid, from, to);
    }

    @PatchMapping("/schedule/task-schedules/{scheduleId}/status")
    public Result<String> updateTaskScheduleStatus(@PathVariable Long scheduleId, @RequestParam Integer status) {
        return scheduleClient.updateTaskScheduleStatus(scheduleId, status);
    }

    @DeleteMapping("/schedule/task-schedules/by-date")
    public Result<String> deleteTaskSchedulesByDate(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam String date) {
        return scheduleClient.deleteTaskSchedulesByDate(resolveUserId(jwt, headerUserId, userId), date);
    }

    @DeleteMapping("/schedule/task-schedules/future")
    public Result<String> deleteFutureTaskSchedules(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return scheduleClient.deleteFutureTaskSchedules(resolveUserId(jwt, headerUserId, userId));
    }

    @PostMapping("/schedule/task-schedules/delete-by-task-ids")
    public Result<String> deleteTaskSchedulesByTaskIds(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody(required = false) List<Long> taskIds) {
        return scheduleClient.deleteTaskSchedulesByTaskIds(resolveUserId(jwt, headerUserId, userId), taskIds);
    }

    @GetMapping("/schedule/classes")
    public Result<List<ClassScheduleDto>> listClasses(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer dayOfWeek) {
        return scheduleClient.listClasses(resolveUserId(jwt, headerUserId, userId), dayOfWeek);
    }

    @DeleteMapping("/schedule/classes")
    public Result<String> deleteClasses(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return scheduleClient.deleteClasses(resolveUserId(jwt, headerUserId, userId));
    }

    @GetMapping("/punch/records")
    public Result<List<PunchRecordDto>> listPunchRecords(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return punchClient.listRecords(resolveUserId(jwt, headerUserId, userId), null, null, null);
    }

    @DeleteMapping("/punch/records/{recordId}")
    public Result<String> deletePunchRecord(@PathVariable Long recordId) {
        return punchClient.deleteRecord(recordId);
    }

    @GetMapping("/punch/streak")
    public Result<Long> getStreak(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return punchClient.getStreak(resolveUserId(jwt, headerUserId, userId));
    }

    @GetMapping("/punch/habits")
    public Result<UserHabitDto> getHabits(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return punchClient.getHabits(resolveUserId(jwt, headerUserId, userId));
    }

    @PutMapping("/punch/habits")
    public Result<UserHabitDto> updateHabits(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer morningPersonScore,
            @RequestParam(required = false) Integer focusDurationAvg,
            @RequestParam(required = false) Float procrastinationIndex) {
        return punchClient.updateHabits(resolveUserId(jwt, headerUserId, userId), morningPersonScore, focusDurationAvg, procrastinationIndex);
    }

    @GetMapping("/goals/{goalId}")
    public Result<GoalDto> getGoal(@PathVariable Long goalId) {
        return goalClient.getGoal(goalId);
    }

    @PutMapping("/goals/{goalId}")
    public Result<String> updateGoal(
            @PathVariable Long goalId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String deadline) {
        return goalClient.updateGoal(goalId, title, description, status, deadline);
    }

    @DeleteMapping("/goals/{goalId}")
    public Result<String> deleteGoal(@PathVariable Long goalId) {
        return goalClient.deleteGoal(goalId);
    }

    @PostMapping("/goals/{goalId}/tasks")
    public Result<GoalTaskDto> createTask(
            @PathVariable Long goalId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long parentId,
            @RequestParam String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Integer priority,
            @RequestParam(required = false) Integer estimatedMinutes,
            @RequestParam(required = false) String deadline) {
        return goalClient.createTask(goalId, resolveUserId(jwt, headerUserId, userId), parentId, title, description, priority, estimatedMinutes, deadline);
    }

    @GetMapping("/goals/{goalId}/tasks")
    public Result<List<GoalTaskDto>> listTasks(
            @PathVariable Long goalId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return goalClient.listTasks(goalId, resolveUserId(jwt, headerUserId, userId));
    }

    @PostMapping("/goals/{goalId}/tasks/regenerate")
    public Result<String> regenerateTasks(
            @PathVariable Long goalId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody(required = false) String feedback) {
        Long uid = resolveUserId(jwt, headerUserId, userId);
        return goalClient.regenerateTasks(goalId, uid, feedback);
    }

    @PatchMapping("/tasks/{taskId}/status")
    public Result<String> updateTaskStatus(@PathVariable Long taskId, @RequestParam Integer status) {
        return goalClient.updateTaskStatus(taskId, status);
    }

    private Long resolveUserId(Jwt jwt, Long headerUserId, Long userId) {
        Long jwtUserId = null;
        if (jwt != null) {
            Object claim = jwt.getClaims().get("userId");
            if (claim != null) {
                jwtUserId = Long.valueOf(String.valueOf(claim));
            }
        }

        if (jwtUserId != null) {
            if (headerUserId != null && !jwtUserId.equals(headerUserId)) {
                throw new IllegalArgumentException("userId mismatch");
            }
            if (userId != null && !jwtUserId.equals(userId)) {
                throw new IllegalArgumentException("userId mismatch");
            }
            return jwtUserId;
        }

        if (headerUserId != null && userId != null && !headerUserId.equals(userId)) {
            throw new IllegalArgumentException("userId mismatch");
        }
        if (headerUserId != null) {
            return headerUserId;
        }
        if (userId != null) {
            return userId;
        }
        throw new IllegalArgumentException("userId required");
    }
}
