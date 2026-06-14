package com.chao.goal.service;

import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.client.ResourceClient;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.GoalTaskDto;
import com.chao.goal.config.AiConfig;
import com.chao.goal.config.AiPromptConfig;
import com.chao.goal.entity.Goal;
import com.chao.goal.entity.GoalTask;
import com.chao.goal.entity.UserJournal;
import com.chao.goal.mapper.GoalMapper;
import com.chao.goal.mapper.GoalTaskMapper;
import com.chao.goal.mapper.UserJournalMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GoalServiceRefactorTest {

    private GoalMapper goalMapper;
    private GoalTaskMapper goalTaskMapper;
    private UserJournalMapper userJournalMapper;
    private ScheduleClient scheduleClient;
    private RabbitTemplate rabbitTemplate;
    private ObjectMapper objectMapper;
    private AiPromptConfig aiPromptConfig;
    private AiConfig aiConfig;

    private AiResponseParser aiResponseParser;
    private JournalService journalService;
    private GoalCrudService goalCrudService;
    private TaskService taskService;
    private AiTaskOrchestrator aiTaskOrchestrator;

    private final Map<String, Object> goalMapperStubs = new HashMap<>();
    private final Map<String, Object> goalTaskMapperStubs = new HashMap<>();
    private final Map<String, Object> journalMapperStubs = new HashMap<>();
    private final Map<String, Object> scheduleStubs = new HashMap<>();
    private final Map<String, Object> rabbitStubs = new HashMap<>();

    private final List<Goal> insertedGoals = new ArrayList<>();
    private final List<GoalTask> insertedTasks = new ArrayList<>();
    private final List<UserJournal> insertedJournals = new ArrayList<>();
    private final List<Object> rabbitSent = new ArrayList<>();

    private static Object nullDefault(Class<?> rt) {
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        return null;
    }

    @BeforeEach
    void setUp() {
        insertedGoals.clear();
        insertedTasks.clear();
        insertedJournals.clear();
        rabbitSent.clear();
        goalMapperStubs.clear();
        goalTaskMapperStubs.clear();
        journalMapperStubs.clear();
        scheduleStubs.clear();
        rabbitStubs.clear();

        goalMapper = (GoalMapper) Proxy.newProxyInstance(
                GoalMapper.class.getClassLoader(), new Class[]{GoalMapper.class},
                (p, m, a) -> {
                    if ("insert".equals(m.getName()) && a != null && a.length > 0 && a[0] instanceof Goal) {
                        Goal g = (Goal) a[0];
                        if (g.getId() == null) g.setId((long) (insertedGoals.size() + 1));
                        insertedGoals.add(g);
                        return 1;
                    }
                    if ("selectById".equals(m.getName())) {
                        Long id = (Long) a[0];
                        return insertedGoals.stream().filter(g -> id.equals(g.getId())).findFirst().orElse(null);
                    }
                    if ("selectList".equals(m.getName())) {
                        return goalMapperStubs.getOrDefault("selectList", insertedGoals);
                    }
                    if ("selectCount".equals(m.getName())) {
                        return goalMapperStubs.getOrDefault("selectCount", 0L);
                    }
                    if ("deleteById".equals(m.getName())) {
                        insertedGoals.removeIf(g -> a[0].equals(g.getId()));
                        return 1;
                    }
                    if ("updateById".equals(m.getName())) {
                        return 1;
                    }
                    return goalMapperStubs.getOrDefault(m.getName(), nullDefault(m.getReturnType()));
                });

        goalTaskMapper = (GoalTaskMapper) Proxy.newProxyInstance(
                GoalTaskMapper.class.getClassLoader(), new Class[]{GoalTaskMapper.class},
                (p, m, a) -> {
                    if ("insert".equals(m.getName()) && a != null && a.length > 0 && a[0] instanceof GoalTask) {
                        GoalTask t = (GoalTask) a[0];
                        if (t.getId() == null) t.setId((long) (insertedTasks.size() + 1));
                        insertedTasks.add(t);
                        return 1;
                    }
                    if ("selectById".equals(m.getName())) {
                        Long id = (Long) a[0];
                        return insertedTasks.stream().filter(t -> id.equals(t.getId())).findFirst().orElse(null);
                    }
                    if ("selectList".equals(m.getName())) {
                        return goalTaskMapperStubs.getOrDefault("selectList", insertedTasks);
                    }
                    if ("selectBatchIds".equals(m.getName())) {
                        @SuppressWarnings("unchecked")
                        List<Long> ids = (List<Long>) a[0];
                        List<GoalTask> result = new ArrayList<>();
                        for (GoalTask t : insertedTasks) {
                            if (ids.contains(t.getId())) result.add(t);
                        }
                        return result;
                    }
                    if ("selectCount".equals(m.getName())) {
                        return goalTaskMapperStubs.getOrDefault("selectCount", (long) insertedTasks.size());
                    }
                    if ("delete".equals(m.getName())) {
                        insertedTasks.clear();
                        return 1;
                    }
                    if ("deleteById".equals(m.getName())) {
                        insertedTasks.removeIf(t -> a[0].equals(t.getId()));
                        return 1;
                    }
                    if ("updateById".equals(m.getName())) {
                        return 1;
                    }
                    return goalTaskMapperStubs.getOrDefault(m.getName(), nullDefault(m.getReturnType()));
                });

        userJournalMapper = (UserJournalMapper) Proxy.newProxyInstance(
                UserJournalMapper.class.getClassLoader(), new Class[]{UserJournalMapper.class},
                (p, m, a) -> {
                    if ("insert".equals(m.getName()) && a != null && a.length > 0 && a[0] instanceof UserJournal) {
                        UserJournal j = (UserJournal) a[0];
                        if (j.getId() == null) j.setId((long) (insertedJournals.size() + 1));
                        insertedJournals.add(j);
                        return 1;
                    }
                    if ("selectById".equals(m.getName())) {
                        Long id = (Long) a[0];
                        return insertedJournals.stream().filter(j -> id.equals(j.getId())).findFirst().orElse(null);
                    }
                    if ("selectList".equals(m.getName())) {
                        return journalMapperStubs.getOrDefault("selectList", insertedJournals);
                    }
                    if ("deleteById".equals(m.getName())) {
                        insertedJournals.removeIf(j -> a[0].equals(j.getId()));
                        return 1;
                    }
                    if ("delete".equals(m.getName())) {
                        insertedJournals.clear();
                        return 1;
                    }
                    return journalMapperStubs.getOrDefault(m.getName(), nullDefault(m.getReturnType()));
                });

        scheduleClient = (ScheduleClient) Proxy.newProxyInstance(
                ScheduleClient.class.getClassLoader(), new Class[]{ScheduleClient.class},
                (p, m, a) -> scheduleStubs.getOrDefault(m.getName(), nullDefault(m.getReturnType())));

        rabbitTemplate = new RabbitTemplate() {
            @Override
            public void convertAndSend(String exchange, String routingKey, Object message) {
                rabbitSent.add(message);
            }
        };

        objectMapper = new ObjectMapper();

        aiPromptConfig = new AiPromptConfig();
        aiPromptConfig.setSystem("test-system-prompt");
        aiPromptConfig.setAdvancedSystem("test-advanced-prompt");

        aiConfig = new AiConfig();
        aiConfig.setDecomposeTimeoutSeconds(90);

        aiResponseParser = new AiResponseParser();

        journalService = new JournalService(userJournalMapper, rabbitTemplate);

        goalCrudService = new GoalCrudService(goalMapper, goalTaskMapper, userJournalMapper, scheduleClient);

        taskService = new TaskService(goalTaskMapper, rabbitTemplate);

        aiTaskOrchestrator = new AiTaskOrchestrator(
                goalMapper, goalTaskMapper, userJournalMapper,
                rabbitTemplate, aiPromptConfig);
    }

    // ── AiResponseParser tests ──

    @Test
    void testSanitizeTasksNull() {
        assertTrue(aiResponseParser.sanitizeTasks(null).isEmpty());
    }

    @Test
    void testSanitizeTasksEmpty() {
        assertTrue(aiResponseParser.sanitizeTasks(List.of()).isEmpty());
    }

    @Test
    void testSanitizeTasksValid() {
        GoalTaskDto t = new GoalTaskDto();
        t.setTitle("阅读：《CSAPP》第3章");
        t.setDescription("完成课后习题");
        t.setEstimatedMinutes(90);
        t.setPriority(2);
        List<GoalTaskDto> result = aiResponseParser.sanitizeTasks(List.of(t));
        assertEquals(1, result.size());
        assertEquals("阅读：《CSAPP》第3章", result.get(0).getTitle());
    }

    @Test
    void testSanitizeTasksFiltersForbiddenTitle() {
        GoalTaskDto t = new GoalTaskDto();
        t.setTitle("制定学习计划");
        t.setDescription("完成课后习题");
        t.setEstimatedMinutes(45);
        t.setPriority(1);
        assertTrue(aiResponseParser.sanitizeTasks(List.of(t)).isEmpty());
    }

    @Test
    void testSanitizeTasksFiltersDateInTitle() {
        GoalTaskDto t = new GoalTaskDto();
        t.setTitle("2026-06-09 学习Python");
        t.setDescription("完成课后习题");
        t.setEstimatedMinutes(45);
        t.setPriority(1);
        assertTrue(aiResponseParser.sanitizeTasks(List.of(t)).isEmpty());
    }

    @Test
    void testSanitizeTasksFiltersTimeInDescription() {
        GoalTaskDto t = new GoalTaskDto();
        t.setTitle("学习Python");
        t.setDescription("在 08:00 开始学习");
        t.setEstimatedMinutes(45);
        t.setPriority(1);
        assertTrue(aiResponseParser.sanitizeTasks(List.of(t)).isEmpty());
    }

    @Test
    void testSanitizeTasksClampsEstimatedMinutes() {
        GoalTaskDto t = new GoalTaskDto();
        t.setTitle("学习Python");
        t.setDescription("完成练习");
        t.setEstimatedMinutes(10);
        t.setPriority(1);
        List<GoalTaskDto> result = aiResponseParser.sanitizeTasks(List.of(t));
        assertEquals(45, result.get(0).getEstimatedMinutes());

        t.setEstimatedMinutes(300);
        result = aiResponseParser.sanitizeTasks(List.of(t));
        assertEquals(45, result.get(0).getEstimatedMinutes());

        t.setEstimatedMinutes(null);
        result = aiResponseParser.sanitizeTasks(List.of(t));
        assertEquals(45, result.get(0).getEstimatedMinutes());
    }

    @Test
    void testSanitizeTasksClampsPriority() {
        GoalTaskDto t = new GoalTaskDto();
        t.setTitle("学习Python");
        t.setDescription("完成练习");
        t.setEstimatedMinutes(45);
        t.setPriority(5);
        List<GoalTaskDto> result = aiResponseParser.sanitizeTasks(List.of(t));
        assertEquals(2, result.get(0).getPriority());

        t.setPriority(-1);
        result = aiResponseParser.sanitizeTasks(List.of(t));
        assertEquals(0, result.get(0).getPriority());

        t.setPriority(null);
        result = aiResponseParser.sanitizeTasks(List.of(t));
        assertEquals(1, result.get(0).getPriority());
    }

    @Test
    void testSanitizeTasksRecursiveSubTasks() {
        GoalTaskDto sub = new GoalTaskDto();
        sub.setTitle("子任务：学习基础语法");
        sub.setDescription("完成10道题");
        sub.setEstimatedMinutes(30);
        sub.setPriority(1);

        GoalTaskDto parent = new GoalTaskDto();
        parent.setTitle("主任务：Python入门");
        parent.setDescription("完成练习");
        parent.setEstimatedMinutes(60);
        parent.setPriority(2);
        parent.setSubTasks(List.of(sub));

        List<GoalTaskDto> result = aiResponseParser.sanitizeTasks(List.of(parent));
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getSubTasks().size());
        assertEquals("子任务：学习基础语法", result.get(0).getSubTasks().get(0).getTitle());
    }

    @Test
    void testSanitizeTasksFiltersForbiddenSubTask() {
        GoalTaskDto badSub = new GoalTaskDto();
        badSub.setTitle("安排日程");
        badSub.setDescription("...");
        badSub.setEstimatedMinutes(30);
        badSub.setPriority(1);

        GoalTaskDto parent = new GoalTaskDto();
        parent.setTitle("主任务：Python入门");
        parent.setDescription("完成练习");
        parent.setEstimatedMinutes(60);
        parent.setPriority(2);
        parent.setSubTasks(List.of(badSub));

        List<GoalTaskDto> result = aiResponseParser.sanitizeTasks(List.of(parent));
        assertEquals(1, result.size());
        assertTrue(result.get(0).getSubTasks().isEmpty());
    }

    @Test
    void testSanitizeOneNullTitle() {
        GoalTaskDto t = new GoalTaskDto();
        t.setTitle(null);
        t.setDescription("desc");
        assertNull(aiResponseParser.sanitizeOne(t));
    }

    @Test
    void testSanitizeOneBlankTitle() {
        GoalTaskDto t = new GoalTaskDto();
        t.setTitle("   ");
        t.setDescription("desc");
        assertNull(aiResponseParser.sanitizeOne(t));
    }

    @Test
    void testContainsDateOrTime() {
        assertTrue(aiResponseParser.containsDateOrTime("2026-06-09"));
        assertTrue(aiResponseParser.containsDateOrTime("在 08:00 开始"));
        assertFalse(aiResponseParser.containsDateOrTime("学习Python"));
        assertFalse(aiResponseParser.containsDateOrTime(null));
        assertFalse(aiResponseParser.containsDateOrTime(""));
    }

    // ── GoalCrudService tests ──

    @Test
    void testCreateGoal() {
        Goal g = goalCrudService.createGoal(1L, "测试目标", "描述", LocalDateTime.now().plusDays(7));
        assertNotNull(g.getId());
        assertEquals("测试目标", g.getTitle());
        assertEquals(1L, g.getUserId());
        assertEquals(1, insertedGoals.size());
    }

    @Test
    void testListGoals() {
        goalCrudService.createGoal(1L, "目标A", "descA", null);
        goalCrudService.createGoal(1L, "目标B", "descB", null);
        List<Goal> goals = goalCrudService.listGoals(1L);
        assertEquals(2, goals.size());
    }

    @Test
    void testGetGoal() {
        Goal created = goalCrudService.createGoal(1L, "测试", "desc", null);
        Goal found = goalCrudService.getGoal(created.getId());
        assertNotNull(found);
        assertEquals(created.getId(), found.getId());
    }

    @Test
    void testUpdateGoal() {
        Goal created = goalCrudService.createGoal(1L, "原标题", "原描述", null);
        goalCrudService.updateGoal(created.getId(), "新标题", "新描述", 1, LocalDateTime.now());
    }

    @Test
    void testDeleteGoal() {
        Goal created = goalCrudService.createGoal(1L, "测试", "desc", null);
        assertFalse(insertedGoals.isEmpty());
        goalCrudService.deleteGoal(created.getId());
        assertTrue(insertedGoals.isEmpty());
    }

    @Test
    void testCountUnfinishedTasks() {
        Goal created = goalCrudService.createGoal(1L, "目标", "desc", null);
        goalTaskMapperStubs.put("selectCount", 3L);
        assertEquals(3L, goalCrudService.countUnfinishedTasks(created.getId()));
    }

    @Test
    void testGetDistinctGoalTopics() {
        goalCrudService.createGoal(1L, "Python学习", "desc", null);
        goalCrudService.createGoal(1L, "Java学习", "desc", null);
        goalCrudService.createGoal(1L, "Python学习", "desc", null);
        List<String> topics = goalCrudService.getDistinctGoalTopics();
        assertEquals(2, topics.size());
        assertTrue(topics.contains("Python学习"));
        assertTrue(topics.contains("Java学习"));
    }

    @Test
    void testListJournals() {
        journalService.save(1L, 10L, "随笔A", "开心");
        journalService.save(1L, 10L, "随笔B", "平静");
        List<UserJournal> journals = goalCrudService.listJournals(1L, 10L);
        assertEquals(2, journals.size());
    }

    // ── TaskService tests ──

    @Test
    void testCreateTaskSuccess() {
        Goal created = goalCrudService.createGoal(1L, "目标", "desc", null);
        GoalTask task = taskService.createTask(1L, created.getId(), null, "学习Python基础", "完成练习", 1, 45, null);
        assertNotNull(task.getId());
        assertEquals("学习Python基础", task.getTitle());
        assertEquals(1, insertedTasks.size());
    }

    @Test
    void testCreateTaskEmptyTitleThrows() {
        Goal created = goalCrudService.createGoal(1L, "目标", "desc", null);
        assertThrows(IllegalArgumentException.class, () ->
                taskService.createTask(1L, created.getId(), null, "", "desc", 1, 45, null));
    }

    @Test
    void testCreateTaskDuplicateExactTitle() {
        Goal created = goalCrudService.createGoal(1L, "目标", "desc", null);
        taskService.createTask(1L, created.getId(), null, "学习Python基础", "desc", 1, 45, null);
        assertThrows(TaskService.DuplicateTaskException.class, () ->
                taskService.createTask(1L, created.getId(), null, "学习Python基础", "desc", 1, 45, null));
    }

    @Test
    void testCreateTaskDuplicateNormalizedTitle() {
        Goal created = goalCrudService.createGoal(1L, "目标", "desc", null);
        taskService.createTask(1L, created.getId(), null, "学习Python基础！", "desc", 1, 45, null);
        assertThrows(TaskService.DuplicateTaskException.class, () ->
                taskService.createTask(1L, created.getId(), null, "学习Python基础", "desc", 1, 45, null));
    }

    @Test
    void testCreateTaskDuplicateSubstring() {
        Goal created = goalCrudService.createGoal(1L, "目标", "desc", null);
        taskService.createTask(1L, created.getId(), null, "学习Python基础语法与高级特性", "desc", 1, 45, null);
        assertThrows(TaskService.DuplicateTaskException.class, () ->
                taskService.createTask(1L, created.getId(), null, "学习Python基础", "desc", 1, 45, null));
    }

    @Test
    void testDuplicateTaskExceptionHasIds() {
        TaskService.DuplicateTaskException ex = new TaskService.DuplicateTaskException(42L, "测试任务");
        assertEquals(42L, ex.getExistingTaskId());
        assertEquals("测试任务", ex.getExistingTitle());
    }

    @Test
    void testCreateTaskWithNullGoalId() {
        GoalTask task = taskService.createTask(1L, null, null, "学习Python", "desc", 1, 45, null);
        assertNotNull(task.getId());
    }

    @Test
    void testListTasksByGoal() {
        Goal created = goalCrudService.createGoal(1L, "目标", "desc", null);
        taskService.createTask(1L, created.getId(), null, "任务A", "desc", 1, 45, null);
        taskService.createTask(1L, created.getId(), null, "任务B", "desc", 1, 30, null);
        List<GoalTask> tasks = taskService.listTasksByGoal(1L, created.getId());
        assertEquals(2, tasks.size());
    }

    @Test
    void testUpdateTaskStatus() {
        Goal created = goalCrudService.createGoal(1L, "目标", "desc", null);
        GoalTask task = taskService.createTask(1L, created.getId(), null, "任务A", "desc", 1, 45, null);
        taskService.updateTaskStatus(task.getId(), 1);
    }

    @Test
    void testGetPendingTasks() {
        Goal created = goalCrudService.createGoal(1L, "目标", "desc", null);
        GoalTask t1 = taskService.createTask(1L, created.getId(), null, "任务A", "desc", 1, 45, null);
        taskService.createTask(1L, created.getId(), null, "任务B", "desc", 1, 45, null);
        List<GoalTaskDto> pending = taskService.getPendingTasks(1L);
        assertEquals(2, pending.size());
        assertEquals(t1.getId(), pending.get(0).getId());
        assertEquals("任务A", pending.get(0).getTitle());
        assertEquals(0, pending.get(0).getStatus());
        assertNotNull(pending.get(0).getEstimatedMinutes());
    }

    @Test
    void testGetTasksByIds() {
        Goal created = goalCrudService.createGoal(1L, "目标", "desc", null);
        GoalTask t1 = taskService.createTask(1L, created.getId(), null, "任务A", "desc", 1, 45, null);
        GoalTask t2 = taskService.createTask(1L, created.getId(), null, "任务B", "desc", 1, 30, null);
        List<GoalTaskDto> dtos = taskService.getTasksByIds(List.of(t1.getId(), t2.getId()));
        assertEquals(2, dtos.size());
    }

    @Test
    void testGetTasksByIdsEmpty() {
        assertTrue(taskService.getTasksByIds(null).isEmpty());
        assertTrue(taskService.getTasksByIds(List.of()).isEmpty());
    }

    @Test
    void testMoveTaskToGoal() {
        Goal g1 = goalCrudService.createGoal(1L, "目标1", "desc", null);
        Goal g2 = goalCrudService.createGoal(1L, "目标2", "desc", null);
        GoalTask task = taskService.createTask(1L, g1.getId(), null, "任务A", "desc", 1, 45, null);
        taskService.moveTaskToGoal(1L, task.getId(), g2.getId());
    }

    @Test
    void testMoveTaskToGoalWrongUser() {
        Goal g1 = goalCrudService.createGoal(1L, "目标1", "desc", null);
        GoalTask task = taskService.createTask(1L, g1.getId(), null, "任务A", "desc", 1, 45, null);
        assertThrows(IllegalArgumentException.class, () ->
                taskService.moveTaskToGoal(2L, task.getId(), g1.getId()));
    }

    // ── AiTaskOrchestrator tests ──

    @Test
    void testCreateGoalAndStartAi() {
        var dto = aiTaskOrchestrator.createGoalAndStartAi(1L, "学习分布式系统");
        assertNotNull(dto.getId());
        assertEquals("学习分布式系统", dto.getDescription());
        assertEquals(1, rabbitSent.size());
    }

    @Test
    void testCreateGoalAndStartAiTruncatesLongTitle() {
        String longDesc = "A".repeat(200);
        var dto = aiTaskOrchestrator.createGoalAndStartAi(1L, longDesc);
        assertTrue(dto.getTitle().length() <= 80);
    }

    @Test
    void testCreateGoalAndStartAiNullDescription() {
        var dto = aiTaskOrchestrator.createGoalAndStartAi(1L, null);
        assertEquals("", dto.getDescription());
    }

    @Test
    void testRegenerateTasksWithFeedback() {
        Goal created = goalCrudService.createGoal(1L, "目标", "desc", null);
        taskService.createTask(1L, created.getId(), null, "已完成任务", "desc", 1, 45, null);
        aiTaskOrchestrator.regenerateTasks(1L, created.getId(), "请增加难度");
        assertFalse(rabbitSent.isEmpty());
    }

    @Test
    void testRegenerateTasksGoalNotFound() {
        assertThrows(IllegalArgumentException.class, () ->
                aiTaskOrchestrator.regenerateTasks(1L, 999L, null));
    }

    // ── JournalService tests ──

    @Test
    void testSaveJournal() {
        UserJournal j = journalService.save(1L, 10L, "今天学习了Spring Boot", "开心");
        assertNotNull(j.getId());
        assertEquals(1L, j.getUserId());
        assertEquals(10L, j.getGoalId());
        assertEquals("今天学习了Spring Boot", j.getContent());
        assertEquals("开心", j.getMood());
        assertEquals(1, rabbitSent.size());
    }

    @Test
    void testSaveJournalNegativeTriggersReminder() {
        journalService.save(1L, 10L, "今天感觉很焦虑，压力很大", "沮丧");
        assertEquals(2, rabbitSent.size());
    }

    @Test
    void testSaveJournalNoNegativeMood() {
        journalService.save(1L, 10L, "今天状态很好", "开心");
        assertEquals(1, rabbitSent.size());
    }

    @Test
    void testDeleteJournal() {
        UserJournal j = journalService.save(1L, 10L, "内容", "心情");
        assertFalse(insertedJournals.isEmpty());
        journalService.delete(1L, j.getId());
        assertTrue(insertedJournals.isEmpty());
    }

    @Test
    void testDeleteJournalWrongUser() {
        UserJournal j = journalService.save(1L, 10L, "内容", "心情");
        assertThrows(IllegalArgumentException.class, () ->
                journalService.delete(2L, j.getId()));
    }

    @Test
    void testDeleteJournalInvalidId() {
        assertThrows(IllegalArgumentException.class, () ->
                journalService.delete(1L, null));
        assertThrows(IllegalArgumentException.class, () ->
                journalService.delete(1L, 0L));
    }

    @Test
    void testDeleteJournalNotFound() {
        assertThrows(IllegalArgumentException.class, () ->
                journalService.delete(1L, 999L));
    }

    // ── AiConfig / AiPromptConfig tests ──

    @Test
    void testAiPromptConfig() {
        assertEquals("test-system-prompt", aiPromptConfig.getSystem());
        assertEquals("test-advanced-prompt", aiPromptConfig.getAdvancedSystem());
    }

    @Test
    void testAiConfigDefaults() {
        AiConfig cfg = new AiConfig();
        assertEquals(90, cfg.getDecomposeTimeoutSeconds());
    }
}
