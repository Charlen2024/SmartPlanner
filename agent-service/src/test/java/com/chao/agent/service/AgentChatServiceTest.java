package com.chao.agent.service;

import com.chao.common.client.GoalClient;
import com.chao.common.client.PunchClient;
import com.chao.common.dto.*;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AgentChatServiceTest {

    private static Object nullDefault(Class<?> rt) {
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        return null;
    }

    @Test
    void ensureUserRagIndexed_shouldNotIndexCourseDocuments() {
        List<Document> capturedDocs = new ArrayList<>();
        List<String> deletedFilters = new ArrayList<>();

        // --- mock VectorStore ---
        VectorStore vs = (VectorStore) Proxy.newProxyInstance(
                VectorStore.class.getClassLoader(),
                new Class[]{VectorStore.class},
                (proxy, method, args) -> {
                    if ("add".equals(method.getName()) && args != null && args.length > 0 && args[0] instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Document> docs = (List<Document>) args[0];
                        capturedDocs.addAll(docs);
                    }
                    if ("delete".equals(method.getName()) && args != null && args.length > 0
                            && args[0] instanceof org.springframework.ai.vectorstore.filter.Filter.Expression expr) {
                        deletedFilters.add(expr.toString());
                    }
                    return nullDefault(method.getReturnType());
                }
        );

        // --- mock ObjectProvider<VectorStore> ---
        @SuppressWarnings("unchecked")
        ObjectProvider<VectorStore> vsProvider = (ObjectProvider<VectorStore>) Proxy.newProxyInstance(
                ObjectProvider.class.getClassLoader(),
                new Class[]{ObjectProvider.class},
                (proxy, method, args) -> "getIfAvailable".equals(method.getName()) ? vs : nullDefault(method.getReturnType())
        );

        // --- mock RBucket ---
        RBucket<String> bucket = (RBucket<String>) Proxy.newProxyInstance(
                RBucket.class.getClassLoader(),
                new Class[]{RBucket.class},
                (proxy, method, args) -> {
                    if ("get".equals(method.getName())) return null; // not indexed yet
                    return nullDefault(method.getReturnType());
                }
        );

        // --- mock RedissonClient ---
        RedissonClient redisson = (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class[]{RedissonClient.class},
                (proxy, method, args) -> "getBucket".equals(method.getName()) ? bucket : nullDefault(method.getReturnType())
        );

        // --- mock GoalClient ---
        GoalClient goalClient = (GoalClient) Proxy.newProxyInstance(
                GoalClient.class.getClassLoader(),
                new Class[]{GoalClient.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("listGoals".equals(name)) {
                        GoalDto g = new GoalDto();
                        g.setId(1L);
                        g.setTitle("Learn Spring");
                        g.setDescription("Master Spring Boot");
                        return Result.success(List.of(g));
                    }
                    if ("getPendingTasks".equals(name)) {
                        GoalTaskDto t = new GoalTaskDto();
                        t.setId(10L);
                        t.setGoalId(1L);
                        t.setTitle("Read ch3");
                        t.setDescription("Chapter 3 of Spring in Action");
                        return Result.success(List.of(t));
                    }
                    if ("listJournals".equals(name)) {
                        UserJournalDto j = new UserJournalDto();
                        j.setId(100L);
                        j.setGoalId(1L);
                        j.setContent("Today I learned about DI");
                        return Result.success(List.of(j));
                    }
                    if ("getTasksByIds".equals(name)) {
                        GoalTaskDto t = new GoalTaskDto();
                        t.setId(10L);
                        t.setTitle("Read ch3");
                        return Result.success(List.of(t));
                    }
                    return Result.success(List.of());
                }
        );

        // --- mock PunchClient ---
        PunchClient punchClient = (PunchClient) Proxy.newProxyInstance(
                PunchClient.class.getClassLoader(),
                new Class[]{PunchClient.class},
                (proxy, method, args) -> {
                    if ("listRecords".equals(method.getName())) {
                        PunchRecordDto r = new PunchRecordDto();
                        r.setId(200L);
                        r.setTaskId(10L);
                        r.setDurationSeconds(1800);
                        r.setCreatedAt(java.time.LocalDateTime.now());
                        return Result.success(List.of(r));
                    }
                    return Result.success(List.of());
                }
        );

        AgentRagIndexer indexer = new AgentRagIndexer(
                goalClient,
                punchClient,
                redisson,
                vsProvider,
                null // RestTemplate — not used in ensureUserRagIndexed
        );

        indexer.ensureUserRagIndexed(42L);

        // Assert: no course type documents
        boolean hasCourse = capturedDocs.stream().anyMatch(d -> "course".equals(d.getMetadata().get("type")));
        assertFalse(hasCourse, "should not index course documents — that is now handled globally");

        // Assert: we have goal, task, journal, punch types
        List<String> types = capturedDocs.stream()
                .map(d -> String.valueOf(d.getMetadata().get("type")))
                .distinct()
                .toList();
        assertTrue(types.contains("goal"), "should contain goal docs: " + types);
        assertTrue(types.contains("task"), "should contain task docs: " + types);
        assertTrue(types.contains("journal"), "should contain journal docs: " + types);
        assertTrue(types.contains("punch"), "should contain punch docs: " + types);

        // Assert: delete was called to clean zombie docs before re-indexing
        assertFalse(deletedFilters.isEmpty(), "should call delete to clean old docs before indexing");
        assertTrue(deletedFilters.get(0).contains("userId"), "delete filter should reference userId: " + deletedFilters.get(0));
    }

    @Test
    void ensureUserRagIndexed_shouldSkipWhenAlreadyIndexed() {
        RBucket<String> bucket = (RBucket<String>) Proxy.newProxyInstance(
                RBucket.class.getClassLoader(),
                new Class[]{RBucket.class},
                (proxy, method, args) -> "get".equals(method.getName()) ? "1" : nullDefault(method.getReturnType())
        );

        RedissonClient redisson = (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class[]{RedissonClient.class},
                (proxy, method, args) -> "getBucket".equals(method.getName()) ? bucket : nullDefault(method.getReturnType())
        );

        GoalClient goalClient = (GoalClient) Proxy.newProxyInstance(
                GoalClient.class.getClassLoader(),
                new Class[]{GoalClient.class},
                (proxy, method, args) -> {
                    fail("no client should be called when already indexed");
                    return null;
                }
        );

        PunchClient punchClient = (PunchClient) Proxy.newProxyInstance(
                PunchClient.class.getClassLoader(),
                new Class[]{PunchClient.class},
                (proxy, method, args) -> {
                    fail("no client should be called when already indexed");
                    return null;
                }
        );

        @SuppressWarnings("unchecked")
        ObjectProvider<VectorStore> vsProvider = (ObjectProvider<VectorStore>) Proxy.newProxyInstance(
                ObjectProvider.class.getClassLoader(),
                new Class[]{ObjectProvider.class},
                (proxy, method, args) -> nullDefault(method.getReturnType())
        );

        AgentRagIndexer indexer = new AgentRagIndexer(
                goalClient,
                punchClient,
                redisson,
                vsProvider,
                null // RestTemplate
        );

        // Should return early without error
        indexer.ensureUserRagIndexed(42L);
    }
}
