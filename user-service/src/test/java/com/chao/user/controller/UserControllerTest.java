package com.chao.user.controller;

import com.chao.common.client.GoalClient;
import com.chao.common.client.PunchClient;
import com.chao.common.client.ResourceClient;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class UserControllerTest {

    private static Object nullDefault(Class<?> rt) {
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        return null;
    }

    @Test
    void ensureCoursesIndexed_shouldUseOneDayTTL() throws Exception {
        AtomicReference<String> lastSetValue = new AtomicReference<>();
        AtomicReference<Duration> lastExpireDuration = new AtomicReference<>();
        List<Document> capturedDocs = new ArrayList<>();

        RBucket<String> bucket = (RBucket<String>) Proxy.newProxyInstance(
                RBucket.class.getClassLoader(),
                new Class[]{RBucket.class},
                (proxy, method, args) -> {
                    if ("get".equals(method.getName())) return null;
                    if ("set".equals(method.getName())) {
                        lastSetValue.set(args != null ? String.valueOf(args[0]) : "null");
                        return null;
                    }
                    if ("expire".equals(method.getName()) && args != null && args.length > 0) {
                        if (args[0] instanceof Duration) lastExpireDuration.set((Duration) args[0]);
                        return true;
                    }
                    return nullDefault(method.getReturnType());
                }
        );

        RedissonClient redisson = (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class[]{RedissonClient.class},
                (proxy, method, args) -> "getBucket".equals(method.getName()) ? bucket : nullDefault(method.getReturnType())
        );

        VectorStore vs = (VectorStore) Proxy.newProxyInstance(
                VectorStore.class.getClassLoader(),
                new Class[]{VectorStore.class},
                (proxy, method, args) -> {
                    if ("add".equals(method.getName()) && args != null && args.length > 0
                            && args[0] instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Document> docs = (List<Document>) args[0];
                        capturedDocs.addAll(docs);
                    }
                    return nullDefault(method.getReturnType());
                }
        );

        @SuppressWarnings("unchecked")
        ObjectProvider<VectorStore> vsProvider = (ObjectProvider<VectorStore>) Proxy.newProxyInstance(
                ObjectProvider.class.getClassLoader(),
                new Class[]{ObjectProvider.class},
                (proxy, method, args) -> "getIfAvailable".equals(method.getName()) ? vs : nullDefault(method.getReturnType())
        );

        ResourceClient resourceClient = (ResourceClient) Proxy.newProxyInstance(
                ResourceClient.class.getClassLoader(),
                new Class[]{ResourceClient.class},
                (proxy, method, args) -> {
                    if ("listResources".equals(method.getName())) {
                        CourseResourceDto c = new CourseResourceDto();
                        c.setId(1L);
                        c.setTitle("ML Basics");
                        c.setContentSummary("Introduction to machine learning");
                        c.setPlatform("Coursera");
                        c.setSourceUrl("https://coursera.org/ml");
                        c.setTopic("AI");
                        List<CourseResourceDto> list = new ArrayList<>();
                        list.add(c);
                        Result<List<CourseResourceDto>> r = new Result<>();
                        r.setCode(200);
                        r.setData(list);
                        return r;
                    }
                    return Result.success(List.of());
                }
        );

        GoalClient goalClient = (GoalClient) Proxy.newProxyInstance(
                GoalClient.class.getClassLoader(), new Class[]{GoalClient.class},
                (proxy, method, args) -> Result.success(List.of()));
        ScheduleClient scheduleClient = (ScheduleClient) Proxy.newProxyInstance(
                ScheduleClient.class.getClassLoader(), new Class[]{ScheduleClient.class},
                (proxy, method, args) -> nullDefault(method.getReturnType()));
        PunchClient punchClient = (PunchClient) Proxy.newProxyInstance(
                PunchClient.class.getClassLoader(), new Class[]{PunchClient.class},
                (proxy, method, args) -> nullDefault(method.getReturnType()));

        UserController controller = reflectivelyConstruct(
                UserController.class,
                null, null, null, null, resourceClient,
                null, null, redisson, vsProvider,
                new ObjectMapper(), new RestTemplate()
        );

        Method method = UserController.class.getDeclaredMethod("ensureCoursesIndexed", VectorStore.class);
        method.setAccessible(true);
        boolean result = (boolean) method.invoke(controller, vs);

        assertTrue(result, "should return true after indexing courses");
        assertEquals("1", lastSetValue.get(), "bucket should be set to '1' after successful indexing");

        // Verify TTL was changed from 7 days to 1 day
        assertNotNull(lastExpireDuration.get(), "expire should have been called on the bucket");
        assertEquals(Duration.ofDays(1), lastExpireDuration.get(),
                "course index TTL should be 1 day so new crawler resources appear quickly");
    }

    @SuppressWarnings("unchecked")
    private static <T> T reflectivelyConstruct(Class<T> clazz, Object... args) {
        try {
            var ctors = clazz.getDeclaredConstructors();
            java.util.Arrays.sort(ctors, (a, b) -> b.getParameterCount() - a.getParameterCount());
            var ctor = ctors[0];
            ctor.setAccessible(true);
            return (T) ctor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
