package com.chao.user.controller;

import com.chao.common.client.*;
import com.chao.common.dto.*;
import com.chao.user.dto.TaskResourcesRequest;
import com.chao.user.service.AppUserService;
import com.chao.user.service.UserService;
import com.chao.user.dto.DashboardDto;
import com.chao.user.entity.AppUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.security.oauth2.jwt.Jwt;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UserControllerRefactorTest {

    private GoalClient goalClient;
    private ScheduleClient scheduleClient;
    private PunchClient punchClient;
    private ResourceClient resourceClient;
    private AgentAdviceClient agentAdviceClient;
    private AppUserService appUserService;
    private UserService userService;
    private RedissonClient redissonClient;
    private ObjectMapper objectMapper;
    private Jwt jwt;

    private UserControllerSupport support;
    private GoalController goalController;
    private TaskController taskController;
    private PunchController punchController;
    private ResourceController resourceController;
    private UserController userController;

    private static Object nullDefault(Class<?> rt) {
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        return null;
    }

    @BeforeEach
    void setUp() {
        // Stubs that tests can override
        final Map<String, Object> goalStubs = new HashMap<>();
        final Map<String, Object> punchStubs = new HashMap<>();
        final Map<String, Object> resourceStubs = new HashMap<>();
        final Map<String, Object> agentStubs = new HashMap<>();
        final Map<String, Object> scheduleStubs = new HashMap<>();
        final Map<String, Object> redissonStubs = new HashMap<>();
        final List<Object> buckets = new ArrayList<>();
        final Map<String, Object> userSvcStubs = new HashMap<>();
        final Map<String, Object> appUserStubs = new HashMap<>();

        goalClient = (GoalClient) Proxy.newProxyInstance(
                GoalClient.class.getClassLoader(), new Class[]{GoalClient.class},
                (p, m, a) -> goalStubs.getOrDefault(m.getName(), nullDefault(m.getReturnType())));

        scheduleClient = (ScheduleClient) Proxy.newProxyInstance(
                ScheduleClient.class.getClassLoader(), new Class[]{ScheduleClient.class},
                (p, m, a) -> scheduleStubs.getOrDefault(m.getName(), nullDefault(m.getReturnType())));

        punchClient = (PunchClient) Proxy.newProxyInstance(
                PunchClient.class.getClassLoader(), new Class[]{PunchClient.class},
                (p, m, a) -> punchStubs.getOrDefault(m.getName(), nullDefault(m.getReturnType())));

        resourceClient = (ResourceClient) Proxy.newProxyInstance(
                ResourceClient.class.getClassLoader(), new Class[]{ResourceClient.class},
                (p, m, a) -> resourceStubs.getOrDefault(m.getName(), nullDefault(m.getReturnType())));

        agentAdviceClient = (AgentAdviceClient) Proxy.newProxyInstance(
                AgentAdviceClient.class.getClassLoader(), new Class[]{AgentAdviceClient.class},
                (p, m, a) -> agentStubs.getOrDefault(m.getName(), nullDefault(m.getReturnType())));

        redissonClient = (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(), new Class[]{RedissonClient.class},
                (p, m, a) -> {
                    if ("getBucket".equals(m.getName())) {
                        return buckets.isEmpty() ? null : buckets.get(0);
                    }
                    return redissonStubs.getOrDefault(m.getName(), nullDefault(m.getReturnType()));
                });

        userService = new UserService(null, null, null, null, null) {
            @Override
            public DashboardDto getDashboard(Long uid, String date, String topic) {
                return (DashboardDto) userSvcStubs.getOrDefault("getDashboard", new DashboardDto());
            }
        };

        appUserService = new AppUserService(null, null) {
            @Override
            public AppUser getById(Long uid) {
                return (AppUser) appUserStubs.getOrDefault("getById", null);
            }
            @Override
            public java.util.List<Long> listAllUserIds() {
                return (java.util.List<Long>) appUserStubs.getOrDefault("listAllUserIds", List.of());
            }
        };

        objectMapper = new ObjectMapper();

        jwt = Jwt.withTokenValue("test")
                .header("alg", "HS256")
                .claim("userId", 1L)
                .claim("sub", "demo")
                .build();

        support = new UserControllerSupport(goalClient, punchClient, resourceClient, redissonClient, objectMapper);
        goalController = new GoalController(goalClient, appUserService, support);
        taskController = new TaskController(goalClient, agentAdviceClient, redissonClient, support);
        punchController = new PunchController(punchClient, support);
        resourceController = new ResourceController(resourceClient, support);
        userController = new UserController(userService, appUserService, support);

        // Save stubs maps for sub-tests to use
        this.goalStubs = goalStubs;
        this.punchStubs = punchStubs;
        this.resourceStubs = resourceStubs;
        this.agentStubs = agentStubs;
        this.scheduleStubs = scheduleStubs;
        this.redissonStubs = redissonStubs;
        this.userSvcStubs = userSvcStubs;
        this.appUserStubs = appUserStubs;
        this.bucketsRef = buckets;
    }

    private Map<String, Object> goalStubs;
    private Map<String, Object> punchStubs;
    private Map<String, Object> resourceStubs;
    private Map<String, Object> agentStubs;
    private Map<String, Object> scheduleStubs;
    private Map<String, Object> redissonStubs;
    private Map<String, Object> userSvcStubs;
    private Map<String, Object> appUserStubs;
    private List<Object> bucketsRef;

    // ========== UserControllerSupport.resolveUserId ==========

    @Test
    void resolveUserId_shouldUseJwtPrincipal() {
        assertEquals(1L, support.resolveUserId(jwt, null, null));
    }

    @Test
    void resolveUserId_shouldRejectMismatchedHeader() {
        assertThrows(IllegalArgumentException.class, () ->
                support.resolveUserId(jwt, 2L, null));
    }

    @Test
    void resolveUserId_shouldRejectMismatchedParam() {
        assertThrows(IllegalArgumentException.class, () ->
                support.resolveUserId(jwt, null, 2L));
    }

    @Test
    void resolveUserId_shouldUseHeaderWhenNoJwt() {
        assertEquals(5L, support.resolveUserId(null, 5L, null));
    }

    @Test
    void resolveUserId_shouldUseParamWhenNoJwtOrHeader() {
        assertEquals(5L, support.resolveUserId(null, null, 5L));
    }

    @Test
    void resolveUserId_shouldThrowWhenAllNull() {
        assertThrows(IllegalArgumentException.class, () ->
                support.resolveUserId(null, null, null));
    }

    // ========== UserControllerSupport utility methods ==========

    @Test
    void safeText_shouldReplaceNewlines() {
        assertEquals("hello world", support.safeText("hello\nworld"));
    }

    @Test
    void safeText_shouldReturnEmptyForNull() {
        assertEquals("", support.safeText(null));
    }

    @Test
    void moodCategory_positiveWords() {
        assertTrue(support.moodCategory("今天很开心") > 0);
        assertTrue(support.moodCategory("充实的一天") > 0);
    }

    @Test
    void moodCategory_negativeWords() {
        assertTrue(support.moodCategory("很焦虑") < 0);
        assertTrue(support.moodCategory("压力很大") < 0);
    }

    @Test
    void moodCategory_neutral() {
        assertEquals(0, support.moodCategory(""));
        assertEquals(0, support.moodCategory("今天天气不错"));
    }

    @Test
    void budgetMinutes_shouldComputeCorrectly() {
        LocalDateTime start = LocalDateTime.of(2025, 1, 1, 9, 0);
        LocalDateTime end = LocalDateTime.of(2025, 1, 1, 10, 30);
        assertEquals(90, support.budgetMinutes(start, end));
    }

    @Test
    void budgetMinutes_shouldReturnNullForInvalid() {
        assertNull(support.budgetMinutes(null, LocalDateTime.now()));
        assertNull(support.budgetMinutes(LocalDateTime.now(), null));
    }

    @Test
    void isFallbackUrl_shouldDetectSearchUrls() {
        assertTrue(support.isFallbackUrl("https://www.google.com/search?q=test"));
        assertTrue(support.isFallbackUrl("https://search.bilibili.com/all?keyword=test"));
        assertFalse(support.isFallbackUrl("https://example.com/resource"));
        assertFalse(support.isFallbackUrl(""));
        assertFalse(support.isFallbackUrl(null));
    }

    // ========== GoalController ==========

    @Test
    void listGoals_shouldResolveUserIdAndDelegate() {
        GoalDto g = new GoalDto();
        g.setId(1L);
        g.setTitle("Test Goal");
        goalStubs.put("listGoals", Result.success(List.of(g)));

        Result<List<GoalDto>> result = goalController.listGoals(jwt, null, null);
        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("Test Goal", result.getData().get(0).getTitle());
    }

    @Test
    void createGoal_shouldDelegate() {
        goalStubs.put("createGoalRecord", Result.success(new GoalDto()));
        Result<GoalDto> result = goalController.createGoal(jwt, null, null, "title", null, null);
        assertEquals(200, result.getCode());
    }

    @Test
    void createGoalByAi_shouldRejectWhenScheduleNotImported() {
        AppUser u = new AppUser();
        u.setId(1L);
        u.setScheduleImported(false);
        appUserStubs.put("getById", u);

        Result<GoalDto> result = goalController.createGoalByAi(jwt, null, null, "学习");
        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("课表"));
    }

    @Test
    void createGoalByAi_shouldProceedWhenScheduleImported() {
        AppUser u = new AppUser();
        u.setId(1L);
        u.setScheduleImported(true);
        appUserStubs.put("getById", u);
        goalStubs.put("createGoalByAi", Result.success(new GoalDto()));

        Result<GoalDto> result = goalController.createGoalByAi(jwt, null, null, "学习");
        assertEquals(200, result.getCode());
    }

    @Test
    void listJournals_shouldDelegate() {
        goalStubs.put("listJournals", Result.success(List.of()));
        Result<List<UserJournalDto>> result = goalController.journals(jwt, null, null, null);
        assertEquals(200, result.getCode());
    }

    @Test
    void getGoal_shouldUsePathId() {
        GoalDto g = new GoalDto();
        g.setId(42L);
        goalStubs.put("getGoal", Result.success(g));

        Result<GoalDto> result = goalController.getGoal(42L);
        assertEquals(42L, result.getData().getId());
    }

    @Test
    void deleteGoal_shouldDelegate() {
        goalStubs.put("deleteGoal", Result.success("ok"));
        Result<String> result = goalController.deleteGoal(99L);
        assertEquals(200, result.getCode());
    }

    // ========== TaskController ==========

    @Test
    void pendingTasks_shouldResolveUserId() {
        goalStubs.put("getPendingTasks", Result.success(List.of()));
        Result<List<GoalTaskDto>> result = taskController.pendingTasks(jwt, null, null);
        assertEquals(200, result.getCode());
    }

    @Test
    void tasksByIds_shouldReturnEmptyForNull() {
        Result<List<GoalTaskDto>> result = taskController.tasksByIds(null);
        assertEquals(200, result.getCode());
        assertTrue(result.getData().isEmpty());
    }

    @Test
    void tasksByIds_shouldReturnEmptyForEmptyList() {
        Result<List<GoalTaskDto>> result = taskController.tasksByIds(List.of());
        assertEquals(200, result.getCode());
        assertTrue(result.getData().isEmpty());
    }

    @Test
    void taskAdvice_shouldReturnEmptyForEmptyInput() {
        Result<Map<Long, String>> result = taskController.taskAdvice(List.of());
        assertEquals(200, result.getCode());
        assertTrue(result.getData().isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void taskAdvice_shouldReturnCachedWhenAvailable() {
        RBucket<Object> bucket = (RBucket<Object>) Proxy.newProxyInstance(
                RBucket.class.getClassLoader(), new Class[]{RBucket.class},
                (p, m, a) -> "get".equals(m.getName()) ? "cached advice" : nullDefault(m.getReturnType()));
        bucketsRef.add(bucket);

        Result<Map<Long, String>> result = taskController.taskAdvice(List.of(10L));
        assertEquals("cached advice", result.getData().get(10L));
    }

    @SuppressWarnings("unchecked")
    @Test
    void taskAdvice_shouldFetchUncachedFromAi() {
        RBucket<Object> bucket = (RBucket<Object>) Proxy.newProxyInstance(
                RBucket.class.getClassLoader(), new Class[]{RBucket.class},
                (p, m, a) -> "get".equals(m.getName()) ? null : nullDefault(m.getReturnType()));
        // The code calls set() and expire() on the bucket too
        bucketsRef.add(bucket);

        GoalTaskDto task = new GoalTaskDto();
        task.setId(10L);
        task.setTitle("Test Task");
        goalStubs.put("getTasksByIds", Result.success(List.of(task)));
        agentStubs.put("adviseTasks", Result.success(Map.of(10L, "fresh advice")));

        Result<Map<Long, String>> result = taskController.taskAdvice(List.of(10L));
        assertEquals("fresh advice", result.getData().get(10L));
    }

    @Test
    void updateTaskStatus_shouldDelegate() {
        goalStubs.put("updateTaskStatus", Result.success("ok"));
        Result<String> result = taskController.updateTaskStatus(10L, 2);
        assertEquals(200, result.getCode());
    }

    // ========== PunchController ==========

    @Test
    void getStreak_shouldResolveUserId() {
        punchStubs.put("getStreak", Result.success(7L));
        Result<Long> result = punchController.getStreak(jwt, null, null);
        assertEquals(7L, result.getData());
    }

    @Test
    void getHabits_shouldResolveUserId() {
        UserHabitDto habits = new UserHabitDto();
        habits.setMorningPersonScore(80);
        punchStubs.put("getHabits", Result.success(habits));

        Result<UserHabitDto> result = punchController.getHabits(jwt, null, null);
        assertEquals(80, result.getData().getMorningPersonScore());
    }

    @Test
    void listPunchRecords_shouldResolveUserId() {
        punchStubs.put("listRecords", Result.success(List.of()));
        Result<List<PunchRecordDto>> result = punchController.listPunchRecords(jwt, null, null);
        assertEquals(200, result.getCode());
    }

    @Test
    void deletePunchRecord_shouldDelegate() {
        punchStubs.put("deleteRecord", Result.success("ok"));
        Result<String> result = punchController.deletePunchRecord(5L);
        assertEquals(200, result.getCode());
    }

    // ========== ResourceController ==========

    @Test
    void searchResources_shouldDelegate() {
        SearchResourceItem cr = new SearchResourceItem();
        cr.setTitle("Test Course");
        resourceStubs.put("searchOnlineCourses", Result.success(List.of(cr)));

        Result<List<SearchResourceItem>> result = resourceController.searchResources("Java");
        assertEquals(1, result.getData().size());
        assertEquals("Test Course", result.getData().get(0).getTitle());
    }

    @Test
    void createResource_shouldDelegate() {
        resourceStubs.put("createResource", Result.success(new CourseResourceDto()));
        Result<CourseResourceDto> result = resourceController.createResource("topic", "title", null, null, null);
        assertEquals(200, result.getCode());
    }

    @Test
    void listResources_shouldDelegate() {
        resourceStubs.put("listResources", Result.success(List.of()));
        Result<List<CourseResourceDto>> result = resourceController.listResources(null);
        assertEquals(200, result.getCode());
    }

    @Test
    void deleteResource_shouldDelegate() {
        resourceStubs.put("deleteResource", Result.success("ok"));
        Result<String> result = resourceController.deleteResource(3L);
        assertEquals(200, result.getCode());
    }

    // ========== UserController (dashboard + internal) ==========

    @Test
    void dashboard_shouldResolveUserIdAndDelegate() {
        userSvcStubs.put("getDashboard", new DashboardDto());
        Result<DashboardDto> result = userController.dashboard(jwt, null, null, null, null);
        assertEquals(200, result.getCode());
    }

    @Test
    void getAllUserIds_shouldReturnAll() {
        appUserStubs.put("listAllUserIds", List.of(1L, 2L, 3L));
        Result<List<Long>> result = userController.getAllUserIds();
        assertEquals(3, result.getData().size());
    }

    // ========== Build schedule preference (integration-like) ==========

    @Test
    void buildSchedulePreference_shouldUseDefaultWhenNoHabits() {
        punchStubs.put("getHabits", Result.success(null));
        SchedulePreferenceDto pref = support.buildSchedulePreference(1L);
        assertNull(pref); // no habits → null
    }

    @Test
    void buildSchedulePreference_shouldComputeFromHabits() {
        UserHabitDto habits = new UserHabitDto();
        habits.setProcrastinationIndex(0.5f);
        habits.setFocusDurationAvg(60);
        punchStubs.put("getHabits", Result.success(habits));
        punchStubs.put("getStreak", Result.success(10L));

        SchedulePreferenceDto pref = support.buildSchedulePreference(1L);
        assertNotNull(pref);
        assertEquals(45, pref.getFocusMinutes()); // 60 falls in 40-70 range → 45
        assertEquals(10, pref.getBreakMinutes());
        assertEquals(240, pref.getMaxDailyMinutes()); // streak >= 3 → 240
    }
}
