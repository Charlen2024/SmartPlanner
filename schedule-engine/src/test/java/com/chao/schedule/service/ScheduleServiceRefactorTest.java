package com.chao.schedule.service;

import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.client.GoalClient;
import com.chao.common.dto.Result;
import com.chao.common.dto.GoalDto;
import com.chao.common.dto.GoalTaskDto;
import com.chao.common.dto.FreeSlotDto;
import com.chao.common.dto.SchedulePreferenceDto;
import com.chao.common.dto.TaskScheduleDto;
import com.chao.common.dto.DailyPlanCommitRequest;
import com.chao.common.dto.GeneratePlanCandidateRequest;
import com.chao.common.client.ScheduleClient;
import com.chao.schedule.config.ScheduleAiConfig;
import com.chao.schedule.config.ScheduleAiPrompts;
import com.chao.schedule.entity.ClassSchedule;
import com.chao.schedule.entity.PlanCandidate;
import com.chao.schedule.entity.TaskSchedule;
import com.chao.schedule.entity.UserScheduleConfig;
import com.chao.schedule.mapper.ClassScheduleMapper;
import com.chao.schedule.mapper.PlanCandidateMapper;
import com.chao.schedule.mapper.TaskScheduleMapper;
import com.chao.schedule.mapper.UserScheduleConfigMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleServiceRefactorTest {

    private ClassScheduleMapper classScheduleMapper;
    private TaskScheduleMapper taskScheduleMapper;
    private PlanCandidateMapper planCandidateMapper;
    private UserScheduleConfigMapper userScheduleConfigMapper;
    private GoalClient goalClient;
    private OpenAiCompatClient openAiCompatClient;
    private ObjectMapper objectMapper;
    private Executor aiTaskExecutor;
    private PlanCandidateWorker planCandidateWorker;
    private RabbitTemplate rabbitTemplate;
    private ScheduleAiConfig aiConfig;
    private ScheduleAiPrompts aiPrompts;
    private ScheduleUtils scheduleUtils;
    private ScheduleValidator scheduleValidator;
    private TaskScheduleService taskScheduleService;
    private FreeTimeCalculator freeTimeCalculator;
    private ScheduleImportService scheduleImportService;
    private ScheduleService scheduleService;
    private DailyPlanJobService dailyPlanJobService;

    private final List<ClassSchedule> classSchedules = new ArrayList<>();
    private final List<TaskSchedule> taskSchedules = new ArrayList<>();
    private final List<PlanCandidate> planCandidates = new ArrayList<>();
    private final List<UserScheduleConfig> userConfigs = new ArrayList<>();
    private final List<Object> rabbitSent = new ArrayList<>();

    private final Map<String, Object> goalStubs = new HashMap<>();
    private final Map<String, Object> openAiStubs = new HashMap<>();
    private final Map<String, Object> classStubs = new HashMap<>();
    private final Map<String, Object> taskStubs = new HashMap<>();
    private final Map<String, Object> candidateStubs = new HashMap<>();
    private final Map<String, Object> configStubs = new HashMap<>();

    private static Object nullDefault(Class<?> rt) {
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        return null;
    }

    @BeforeEach
    void setUp() {
        classSchedules.clear();
        taskSchedules.clear();
        planCandidates.clear();
        userConfigs.clear();
        rabbitSent.clear();
        goalStubs.clear();
        openAiStubs.clear();
        classStubs.clear();
        taskStubs.clear();
        candidateStubs.clear();
        configStubs.clear();

        classScheduleMapper = (ClassScheduleMapper) Proxy.newProxyInstance(
                ClassScheduleMapper.class.getClassLoader(), new Class[]{ClassScheduleMapper.class},
                (p, m, a) -> {
                    if ("insert".equals(m.getName()) && a != null && a.length > 0 && a[0] instanceof ClassSchedule) {
                        ClassSchedule cs = (ClassSchedule) a[0];
                        if (cs.getId() == null) cs.setId((long) (classSchedules.size() + 1));
                        classSchedules.add(cs);
                        return 1;
                    }
                    if ("selectById".equals(m.getName())) {
                        Long id = (Long) a[0];
                        return classSchedules.stream().filter(c -> id.equals(c.getId())).findFirst().orElse(null);
                    }
                    if ("selectList".equals(m.getName())) {
                        return classStubs.getOrDefault("selectList", new ArrayList<>(classSchedules));
                    }
                    if ("selectCount".equals(m.getName())) {
                        return classStubs.getOrDefault("selectCount", (long) classSchedules.size());
                    }
                    if ("delete".equals(m.getName())) {
                        classSchedules.clear();
                        return 1;
                    }
                    if ("deleteById".equals(m.getName())) {
                        classSchedules.removeIf(c -> a[0].equals(c.getId()));
                        return 1;
                    }
                    if ("updateById".equals(m.getName())) {
                        return 1;
                    }
                    return classStubs.getOrDefault(m.getName(), nullDefault(m.getReturnType()));
                });

        taskScheduleMapper = (TaskScheduleMapper) Proxy.newProxyInstance(
                TaskScheduleMapper.class.getClassLoader(), new Class[]{TaskScheduleMapper.class},
                (p, m, a) -> {
                    if ("insert".equals(m.getName()) && a != null && a.length > 0 && a[0] instanceof TaskSchedule) {
                        TaskSchedule ts = (TaskSchedule) a[0];
                        if (ts.getId() == null) ts.setId((long) (taskSchedules.size() + 1));
                        taskSchedules.add(ts);
                        return 1;
                    }
                    if ("selectById".equals(m.getName())) {
                        Long id = (Long) a[0];
                        return taskSchedules.stream().filter(t -> id.equals(t.getId())).findFirst().orElse(null);
                    }
                    if ("selectList".equals(m.getName())) {
                        return taskStubs.getOrDefault("selectList", new ArrayList<>(taskSchedules));
                    }
                    if ("selectCount".equals(m.getName())) {
                        return taskStubs.getOrDefault("selectCount", (long) taskSchedules.size());
                    }
                    if ("delete".equals(m.getName())) {
                        taskSchedules.clear();
                        return 1;
                    }
                    if ("deleteById".equals(m.getName())) {
                        taskSchedules.removeIf(t -> a[0].equals(t.getId()));
                        return 1;
                    }
                    if ("updateById".equals(m.getName())) {
                        return 1;
                    }
                    return taskStubs.getOrDefault(m.getName(), nullDefault(m.getReturnType()));
                });

        planCandidateMapper = (PlanCandidateMapper) Proxy.newProxyInstance(
                PlanCandidateMapper.class.getClassLoader(), new Class[]{PlanCandidateMapper.class},
                (p, m, a) -> {
                    if ("insert".equals(m.getName()) && a != null && a.length > 0 && a[0] instanceof PlanCandidate) {
                        PlanCandidate pc = (PlanCandidate) a[0];
                        if (pc.getId() == null) pc.setId((long) (planCandidates.size() + 1));
                        planCandidates.add(pc);
                        return 1;
                    }
                    if ("selectById".equals(m.getName())) {
                        Long id = (Long) a[0];
                        return planCandidates.stream().filter(pc -> id.equals(pc.getId())).findFirst().orElse(null);
                    }
                    if ("selectList".equals(m.getName())) {
                        return candidateStubs.getOrDefault("selectList", new ArrayList<>(planCandidates));
                    }
                    if ("selectCount".equals(m.getName())) {
                        return candidateStubs.getOrDefault("selectCount", (long) planCandidates.size());
                    }
                    if ("delete".equals(m.getName())) {
                        planCandidates.clear();
                        return 1;
                    }
                    if ("updateById".equals(m.getName()) && a != null && a.length > 0 && a[0] instanceof PlanCandidate) {
                        PlanCandidate incoming = (PlanCandidate) a[0];
                        planCandidates.stream().filter(pc -> incoming.getId().equals(pc.getId())).findFirst()
                                .ifPresent(pc -> {
                                    if (incoming.getStatus() != null) pc.setStatus(incoming.getStatus());
                                    if (incoming.getNote() != null) pc.setNote(incoming.getNote());
                                    if (incoming.getSchedulesJson() != null) pc.setSchedulesJson(incoming.getSchedulesJson());
                                    if (incoming.getSuggestedFreeSlotsJson() != null) pc.setSuggestedFreeSlotsJson(incoming.getSuggestedFreeSlotsJson());
                                    if (incoming.getSuggestedSchedulesJson() != null) pc.setSuggestedSchedulesJson(incoming.getSuggestedSchedulesJson());
                                });
                        return 1;
                    }
                    return candidateStubs.getOrDefault(m.getName(), nullDefault(m.getReturnType()));
                });

        userScheduleConfigMapper = (UserScheduleConfigMapper) Proxy.newProxyInstance(
                UserScheduleConfigMapper.class.getClassLoader(), new Class[]{UserScheduleConfigMapper.class},
                (p, m, a) -> {
                    if ("insertOrUpdate".equals(m.getName()) && a != null && a.length > 0 && a[0] instanceof UserScheduleConfig) {
                        UserScheduleConfig cfg = (UserScheduleConfig) a[0];
                        userConfigs.removeIf(c -> cfg.getUserId().equals(c.getUserId()));
                        userConfigs.add(cfg);
                        return true;
                    }
                    if ("selectById".equals(m.getName())) {
                        Long uid = (Long) a[0];
                        return userConfigs.stream().filter(c -> uid.equals(c.getUserId())).findFirst().orElse(null);
                    }
                    if ("updateById".equals(m.getName()) && a != null && a.length > 0) {
                        if (a[0] instanceof UserScheduleConfig) {
                            UserScheduleConfig incoming = (UserScheduleConfig) a[0];
                            Long uid = incoming.getUserId();
                            userConfigs.stream().filter(c -> uid.equals(c.getUserId())).findFirst()
                                    .ifPresent(c -> c.setFirstWeekMonday(incoming.getFirstWeekMonday()));
                        }
                        return 1;
                    }
                    return configStubs.getOrDefault(m.getName(), nullDefault(m.getReturnType()));
                });

        goalClient = (GoalClient) Proxy.newProxyInstance(
                GoalClient.class.getClassLoader(), new Class[]{GoalClient.class},
                (p, m, a) -> goalStubs.getOrDefault(m.getName(), nullDefault(m.getReturnType())));

        objectMapper = new ObjectMapper();

        openAiCompatClient = new OpenAiCompatClient(null) {
            @Override
            public String complete(String sys, String user) {
                return (String) openAiStubs.getOrDefault("complete", "{}");
            }
            @Override
            public String complete(String prompt) {
                return (String) openAiStubs.getOrDefault("complete", "{}");
            }
        };

        rabbitTemplate = new RabbitTemplate() {
            @Override
            public void convertAndSend(String exchange, String routingKey, Object message) {
                rabbitSent.add(message);
            }
        };

        aiTaskExecutor = Runnable::run;

        aiConfig = new ScheduleAiConfig();
        aiPrompts = new ScheduleAiPrompts();
        scheduleUtils = new ScheduleUtils(objectMapper);
        scheduleValidator = new ScheduleValidator();
        taskScheduleService = new TaskScheduleService(taskScheduleMapper, goalClient);
        freeTimeCalculator = new FreeTimeCalculator(classScheduleMapper, userScheduleConfigMapper);
        scheduleImportService = new ScheduleImportService(classScheduleMapper, userScheduleConfigMapper);

        planCandidateWorker = new PlanCandidateWorker(openAiCompatClient, planCandidateMapper, goalClient, aiConfig, aiPrompts, scheduleUtils);

        scheduleService = new ScheduleService(
                classScheduleMapper, taskScheduleMapper, planCandidateMapper,
                userScheduleConfigMapper, goalClient, openAiCompatClient,
                objectMapper, aiTaskExecutor, planCandidateWorker, aiConfig, aiPrompts, scheduleUtils, scheduleValidator, taskScheduleService, freeTimeCalculator, scheduleImportService);

        dailyPlanJobService = new DailyPlanJobService(scheduleService, Runnable::run, rabbitTemplate);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ScheduleValidation tests (pure logic, destined for ScheduleValidator)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void testFilterOverlapsEmpty() {
        assertTrue(scheduleService.filterOverlaps(null).isEmpty());
        assertTrue(scheduleService.filterOverlaps(List.of()).isEmpty());
    }

    @Test
    void testFilterOverlapsNoOverlap() {
        TaskScheduleDto a = makeSchedule(1L, "2026-06-01T08:00", "2026-06-01T09:00");
        TaskScheduleDto b = makeSchedule(2L, "2026-06-01T10:00", "2026-06-01T11:00");
        List<TaskScheduleDto> result = scheduleService.filterOverlaps(List.of(a, b));
        assertEquals(2, result.size());
    }

    @Test
    void testFilterOverlapsWithOverlap() {
        TaskScheduleDto a = makeSchedule(1L, "2026-06-01T08:00", "2026-06-01T10:00");
        TaskScheduleDto b = makeSchedule(2L, "2026-06-01T09:00", "2026-06-01T11:00");
        List<TaskScheduleDto> result = scheduleService.filterOverlaps(List.of(a, b));
        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getTaskId());
    }

    @Test
    void testEnforceMinGap() {
        TaskScheduleDto a = makeSchedule(1L, "2026-06-01T08:00", "2026-06-01T08:45");
        TaskScheduleDto b = makeSchedule(2L, "2026-06-01T08:50", "2026-06-01T09:30");
        List<TaskScheduleDto> result = scheduleService.enforceMinGap(List.of(a, b), 10);
        assertEquals(2, result.size());
        // First stays, second gets pushed
        assertEquals(LocalDateTime.parse("2026-06-01T08:00"), result.get(0).getStartTime());
        assertFalse(result.get(1).getStartTime().isBefore(result.get(0).getEndTime().plusMinutes(10)));
    }

    @Test
    void testClampDailyMinutes() {
        TaskScheduleDto a = makeSchedule(1L, "2026-06-01T08:00", "2026-06-01T09:00"); // 60 min
        TaskScheduleDto b = makeSchedule(2L, "2026-06-01T09:30", "2026-06-01T11:30"); // 120 min
        List<TaskScheduleDto> result = scheduleService.clampDailyMinutes(List.of(a, b), 90);
        // Should fit first (60min) + cut second to 30min
        assertEquals(2, result.size());
        long totalMin = result.stream()
                .mapToLong(s -> java.time.Duration.between(s.getStartTime(), s.getEndTime()).toMinutes())
                .sum();
        assertTrue(totalMin <= 90);
    }

    @Test
    void testClampDailyMinutesEmpty() {
        assertTrue(scheduleService.clampDailyMinutes(null, 100).isEmpty());
    }

    @Test
    void testSubtractOccupied() {
        FreeSlotDto slot = new FreeSlotDto();
        slot.setStart(LocalDateTime.parse("2026-06-01T08:00"));
        slot.setEnd(LocalDateTime.parse("2026-06-01T12:00"));

        TaskSchedule occ = new TaskSchedule();
        occ.setStartTime(LocalDateTime.parse("2026-06-01T09:00"));
        occ.setEndTime(LocalDateTime.parse("2026-06-01T10:00"));

        List<FreeSlotDto> result = scheduleService.subtractOccupied(List.of(slot), List.of(occ));
        assertFalse(result.isEmpty());
    }

    @Test
    void testSubtractOccupiedEmpty() {
        assertTrue(scheduleService.subtractOccupied(null, null).isEmpty());
        assertTrue(scheduleService.subtractOccupied(List.of(), List.of()).isEmpty());
    }

    @Test
    void testAllWithinFreeSlots() {
        FreeSlotDto slot = new FreeSlotDto();
        slot.setStart(LocalDateTime.parse("2026-06-01T08:00"));
        slot.setEnd(LocalDateTime.parse("2026-06-01T12:00"));

        TaskScheduleDto sched = makeSchedule(1L, "2026-06-01T09:00", "2026-06-01T10:00");
        assertTrue(scheduleService.allWithinFreeSlots(List.of(sched), List.of(slot)));
    }

    @Test
    void testAllWithinFreeSlotsOutside() {
        FreeSlotDto slot = new FreeSlotDto();
        slot.setStart(LocalDateTime.parse("2026-06-01T08:00"));
        slot.setEnd(LocalDateTime.parse("2026-06-01T10:00"));

        TaskScheduleDto sched = makeSchedule(1L, "2026-06-01T09:00", "2026-06-01T11:00");
        assertFalse(scheduleService.allWithinFreeSlots(List.of(sched), List.of(slot)));
    }

    @Test
    void testAllWithinFreeSlotsEmpty() {
        assertTrue(scheduleService.allWithinFreeSlots(null, null));
        assertTrue(scheduleService.allWithinFreeSlots(List.of(), List.of()));
    }

    @Test
    void testNormalizeSlots() {
        FreeSlotDto a = new FreeSlotDto();
        a.setStart(LocalDateTime.parse("2026-06-01T09:00"));
        a.setEnd(LocalDateTime.parse("2026-06-01T11:00"));
        FreeSlotDto b = new FreeSlotDto();
        b.setStart(LocalDateTime.parse("2026-06-01T10:00")); // overlaps with a
        b.setEnd(LocalDateTime.parse("2026-06-01T12:00"));

        List<FreeSlotDto> result = scheduleService.normalizeSlots(LocalDate.parse("2026-06-01"), List.of(a, b));
        assertEquals(1, result.size());
        assertEquals(LocalDateTime.parse("2026-06-01T09:00"), result.get(0).getStart());
        assertEquals(LocalDateTime.parse("2026-06-01T12:00"), result.get(0).getEnd());
    }

    @Test
    void testNormalizeSchedules() {
        TaskScheduleDto a = makeSchedule(1L, "2026-06-01T10:00", "2026-06-01T11:00");
        TaskScheduleDto b = makeSchedule(2L, "2026-06-01T08:00", "2026-06-01T09:00");
        List<TaskScheduleDto> result = scheduleService.normalizeSchedules(List.of(a, b));
        assertEquals(2, result.size());
        assertEquals(2L, result.get(0).getTaskId()); // b comes first (sorted by startTime)
    }

    // ═══════════════════════════════════════════════
    // ScheduleService public method tests
    // ═══════════════════════════════════════════════

    @Test
    void testSaveFirstWeekMonday() {
        scheduleService.saveFirstWeekMonday(1L, LocalDate.parse("2026-03-02"));
        assertEquals(1, userConfigs.size());
        assertEquals(1L, userConfigs.get(0).getUserId());
        assertEquals(LocalDate.parse("2026-03-02"), userConfigs.get(0).getFirstWeekMonday());
    }

    @Test
    void testClearFirstWeekMonday() {
        scheduleService.saveFirstWeekMonday(1L, LocalDate.parse("2026-03-02"));
        assertEquals(1, userConfigs.size());
        scheduleService.clearFirstWeekMonday(1L);
        // Config still exists but firstWeekMonday is null
        assertNull(userConfigs.get(0).getFirstWeekMonday());
    }

    @Test
    void testDeleteClassSchedules() {
        ClassSchedule cs = new ClassSchedule();
        cs.setUserId(1L);
        cs.setCourseName("测试课程");
        cs.setDayOfWeek(1);
        cs.setStartTime(LocalTime.of(8, 0));
        cs.setEndTime(LocalTime.of(9, 40));
        classSchedules.add(cs);
        scheduleService.deleteClassSchedules(1L);
        assertTrue(classSchedules.isEmpty());
    }

    @Test
    void testUpdateTaskScheduleStatus() {
        TaskSchedule ts = new TaskSchedule();
        ts.setId(1L);
        ts.setUserId(1L);
        taskSchedules.add(ts);
        scheduleService.updateTaskScheduleStatus(1L, 1);
        // No exception = success (updateById is stubbed)
    }

    @Test
    void testDeleteFutureTaskSchedules() {
        TaskSchedule ts = new TaskSchedule();
        ts.setId(1L);
        ts.setUserId(1L);
        ts.setStartTime(LocalDateTime.now().plusDays(1));
        taskSchedules.add(ts);
        scheduleService.deleteFutureTaskSchedules(1L);
        assertTrue(taskSchedules.isEmpty());
    }

    @Test
    void testDeleteTaskSchedulesByTaskIds() {
        TaskSchedule ts = new TaskSchedule();
        ts.setId(1L);
        ts.setUserId(1L);
        ts.setTaskId(10L);
        taskSchedules.add(ts);
        scheduleService.deleteTaskSchedulesByTaskIds(1L, List.of(10L));
        assertTrue(taskSchedules.isEmpty());
    }

    @Test
    void testDeleteTaskSchedulesByTaskIdsUnauthorized() {
        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.deleteTaskSchedulesByTaskIds(null, List.of(1L)));
        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.deleteTaskSchedulesByTaskIds(0L, List.of(1L)));
    }

    @Test
    void testDeleteTaskSchedulesByDate() {
        TaskSchedule ts = new TaskSchedule();
        ts.setId(1L);
        ts.setUserId(1L);
        ts.setStartTime(LocalDateTime.parse("2026-06-01T10:00"));
        taskSchedules.add(ts);
        scheduleService.deleteTaskSchedulesByDate(1L, "2026-06-01");
        assertTrue(taskSchedules.isEmpty());
    }

    @Test
    void testDeleteTaskSchedulesByDateUnauthorized() {
        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.deleteTaskSchedulesByDate(null, "2026-06-01"));
        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.deleteTaskSchedulesByDate(0L, "2026-06-01"));
    }

    @Test
    void testDeleteTaskSchedulesByDateEmpty() {
        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.deleteTaskSchedulesByDate(1L, ""));
    }

    // ═══════════════════════════════════════════════
    // ScheduleService calculateFreeTime tests
    // ═══════════════════════════════════════════════

    @Test
    void testCalculateFreeTimeNoClasses() {
        List<ScheduleClient.TimeSlot> slots = scheduleService.calculateFreeTime(1L, "2026-06-01", null);
        assertFalse(slots.isEmpty());
        // Full day free: 08:00 - 22:00
        assertEquals(LocalTime.of(8, 0), slots.get(0).getStart().toLocalTime());
        assertEquals(LocalTime.of(22, 0), slots.get(0).getEnd().toLocalTime());
    }

    @Test
    void testCalculateFreeTimeWithClasses() {
        ClassSchedule cs = new ClassSchedule();
        cs.setUserId(1L);
        cs.setCourseName("测试");
        cs.setDayOfWeek(1); // Monday
        cs.setStartTime(LocalTime.of(8, 0));
        cs.setEndTime(LocalTime.of(10, 0));
        classSchedules.add(cs);

        // 2026-06-01 is a Monday
        List<ScheduleClient.TimeSlot> slots = scheduleService.calculateFreeTime(1L, "2026-06-01", null);
        assertFalse(slots.isEmpty());
        // First slot should start after class ends
        boolean hasAfterClass = slots.stream()
                .anyMatch(s -> !s.getStart().toLocalTime().isBefore(LocalTime.of(10, 0)));
        assertTrue(hasAfterClass, "Should have free time after 10:00");
    }

    @Test
    void testListClassSchedules() {
        ClassSchedule cs = new ClassSchedule();
        cs.setUserId(1L);
        cs.setCourseName("测试");
        cs.setDayOfWeek(1);
        cs.setStartTime(LocalTime.of(8, 0));
        cs.setEndTime(LocalTime.of(10, 0));
        classSchedules.add(cs);

        // 2026-06-01 is a Monday
        List<ClassSchedule> list = scheduleService.listClassSchedules(1L, 1, "2026-06-01", null);
        assertEquals(1, list.size());
    }

    // ═══════════════════════════════════════════════
    // ScheduleService resolvePreference tests
    // ═══════════════════════════════════════════════

    @Test
    void testResolvePreferenceNull() {
        SchedulePreferenceDto result = scheduleService.resolvePreference(null);
        assertNotNull(result);
        assertEquals(45, result.getFocusMinutes());
        assertEquals(10, result.getBreakMinutes());
        assertEquals(240, result.getMaxDailyMinutes());
        assertEquals(0.3f, result.getProcrastinationIndex(), 0.01);
    }

    @Test
    void testResolvePreferencePartial() {
        SchedulePreferenceDto pref = new SchedulePreferenceDto();
        pref.setFocusMinutes(30);
        SchedulePreferenceDto result = scheduleService.resolvePreference(pref);
        assertEquals(30, result.getFocusMinutes());
        assertEquals(10, result.getBreakMinutes()); // defaulted
        assertEquals(240, result.getMaxDailyMinutes()); // defaulted
    }

    @Test
    void testResolvePreferencePreservesValues() {
        SchedulePreferenceDto pref = new SchedulePreferenceDto();
        pref.setFocusMinutes(50);
        pref.setBreakMinutes(5);
        pref.setMaxDailyMinutes(180);
        pref.setProcrastinationIndex(0.8f);
        SchedulePreferenceDto result = scheduleService.resolvePreference(pref);
        assertEquals(50, result.getFocusMinutes());
        assertEquals(5, result.getBreakMinutes());
        assertEquals(180, result.getMaxDailyMinutes());
        assertEquals(0.8f, result.getProcrastinationIndex(), 0.01);
    }

    // ═══════════════════════════════════════════════
    // PlanCandidate management tests
    // ═══════════════════════════════════════════════

    @Test
    void testListPlanCandidatesEmpty() {
        assertTrue(scheduleService.listPlanCandidates(1L, null).isEmpty());
    }

    @Test
    void testListPlanCandidatesWithData() {
        PlanCandidate pc = new PlanCandidate();
        pc.setUserId(1L);
        pc.setPlanDate(LocalDate.parse("2026-06-01"));
        pc.setStatus(0);
        pc.setNote("测试");
        pc.setFreeSlotsJson("[]");
        pc.setSchedulesJson("[]");
        pc.setCreatedAt(LocalDateTime.now());
        planCandidates.add(pc);

        List<com.chao.common.dto.PlanCandidateDto> list = scheduleService.listPlanCandidates(1L, null);
        assertEquals(1, list.size());
        assertEquals("测试", list.get(0).getNote());
    }

    @Test
    void testDecidePlanCandidateNotFound() {
        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.decidePlanCandidate(1L, 999L, true, false));
    }

    @Test
    void testDecidePlanCandidateReject() {
        PlanCandidate pc = new PlanCandidate();
        pc.setId(1L);
        pc.setUserId(1L);
        pc.setPlanDate(LocalDate.parse("2026-06-01"));
        pc.setStatus(0);
        pc.setFreeSlotsJson("[]");
        pc.setSchedulesJson("[]");
        pc.setCreatedAt(LocalDateTime.now());
        planCandidates.add(pc);

        scheduleService.decidePlanCandidate(1L, 1L, false, false);
        assertEquals(2, planCandidates.get(0).getStatus()); // REJECTED
    }

    @Test
    void testDecidePlanCandidateAcceptEmpty() {
        PlanCandidate pc = new PlanCandidate();
        pc.setId(1L);
        pc.setUserId(1L);
        pc.setPlanDate(LocalDate.parse("2026-06-01"));
        pc.setStatus(0);
        pc.setFreeSlotsJson("[]");
        pc.setSchedulesJson("[]");
        pc.setCreatedAt(LocalDateTime.now());
        planCandidates.add(pc);

        // Accepting with empty schedules should throw
        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.decidePlanCandidate(1L, 1L, true, false));
    }

    @Test
    void testGeneratePlanCandidateNoSlots() {
        // No class schedules → should throw
        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.generatePlanCandidate(1L, null));
    }

    // ═══════════════════════════════════════════════
    // SanitizeJsonObject tests
    // ═══════════════════════════════════════════════

    @Test
    void testSanitizeJsonObjectNull() {
        assertEquals("{}", scheduleService.sanitizeJsonObject(null));
    }

    @Test
    void testSanitizeJsonObjectPlain() {
        String json = "{\"note\":\"hello\"}";
        assertEquals(json, scheduleService.sanitizeJsonObject(json));
    }

    @Test
    void testSanitizeJsonObjectMarkdown() {
        String input = "```json\n{\"note\":\"hello\"}\n```";
        assertEquals("{\"note\":\"hello\"}", scheduleService.sanitizeJsonObject(input));
    }

    @Test
    void testSanitizeJsonObjectWithText() {
        String input = "Here is the result: {\"note\":\"hello\"} done.";
        assertEquals("{\"note\":\"hello\"}", scheduleService.sanitizeJsonObject(input));
    }

    // ═══════════════════════════════════════════════
    // PlanCandidateWorker tests
    // ═══════════════════════════════════════════════

    @Test
    void testGenerateRuleBasedSchedulesEmpty() {
        assertTrue(planCandidateWorker.generateRuleBasedSchedules(List.of(), List.of(), null).isEmpty());
        assertTrue(planCandidateWorker.generateRuleBasedSchedules(null, null, null).isEmpty());
    }

    @Test
    void testGenerateRuleBasedSchedulesSingleTask() {
        FreeSlotDto slot = new FreeSlotDto();
        slot.setStart(LocalDateTime.parse("2026-06-01T08:00"));
        slot.setEnd(LocalDateTime.parse("2026-06-01T12:00"));

        GoalTaskDto task = new GoalTaskDto();
        task.setId(1L);
        task.setTitle("测试任务");
        task.setEstimatedMinutes(45);
        task.setPriority(1);

        List<TaskScheduleDto> result = planCandidateWorker.generateRuleBasedSchedules(
                List.of(slot), List.of(task), null);
        assertFalse(result.isEmpty());
        assertEquals(1L, result.get(0).getTaskId());
    }

    @Test
    void testGenerateRuleBasedSchedulesPriorityOrder() {
        FreeSlotDto slot = new FreeSlotDto();
        slot.setStart(LocalDateTime.parse("2026-06-01T08:00"));
        slot.setEnd(LocalDateTime.parse("2026-06-01T12:00"));

        GoalTaskDto lowPri = new GoalTaskDto();
        lowPri.setId(1L);
        lowPri.setTitle("低优先级");
        lowPri.setEstimatedMinutes(45);
        lowPri.setPriority(0);

        GoalTaskDto highPri = new GoalTaskDto();
        highPri.setId(2L);
        highPri.setTitle("高优先级");
        highPri.setEstimatedMinutes(45);
        highPri.setPriority(2);

        List<TaskScheduleDto> result = planCandidateWorker.generateRuleBasedSchedules(
                List.of(slot), List.of(lowPri, highPri), null);
        assertFalse(result.isEmpty());
        // High priority should be scheduled first
        assertEquals(2L, result.get(0).getTaskId());
    }

    // ═══════════════════════════════════════════════
    // DailyPlanJobService tests
    // ═══════════════════════════════════════════════

    @Test
    void testStartJob() {
        var req = new com.chao.common.dto.DailyPlanJobStartRequest();
        req.setDate(LocalDate.parse("2026-06-01"));
        var resp = dailyPlanJobService.start(1L, req);
        assertNotNull(resp.getJobId());
        assertFalse(resp.getJobId().isEmpty());
    }

    @Test
    void testJobStatusNotFound() {
        assertThrows(IllegalArgumentException.class, () ->
                dailyPlanJobService.status(1L, "nonexistent"));
    }

    @Test
    void testWriteJson() {
        // Test the public writeJson via scheduleService
        String json = scheduleService.writeJson(Map.of("key", "value"));
        assertEquals("{\"key\":\"value\"}", json);
    }

    @Test
    void testWriteJsonFailure() {
        // Test with a non-serializable object wrapped
        String json = scheduleService.writeJson(new Object() {
            public String getX() { throw new RuntimeException("fail"); }
        });
        assertNotNull(json);
    }

    // ═══════════════════════════════════════════════
    // Helper
    // ═══════════════════════════════════════════════

    private TaskScheduleDto makeSchedule(long taskId, String start, String end) {
        TaskScheduleDto s = new TaskScheduleDto();
        s.setTaskId(taskId);
        s.setStartTime(LocalDateTime.parse(start));
        s.setEndTime(LocalDateTime.parse(end));
        return s;
    }
}
