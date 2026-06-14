package com.chao.resource.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.client.ResourceClient;
import com.chao.common.dto.SearchResourceItem;
import com.chao.resource.mapper.CourseResourceMapper;
import com.chao.resource.search.CourseResourceSearchRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;

public class ResourceServiceCrawlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void testBilibiliCrawl() throws Exception {
        BilibiliCrawlerService svc = createMinimalService();

        java.lang.reflect.Method httpMethod = BilibiliCrawlerService.class.getDeclaredMethod(
                "httpGetTextWithUA", String.class, String.class, String.class, String.class);
        httpMethod.setAccessible(true);
        String json = (String) httpMethod.invoke(svc,
                "https://api.bilibili.com/x/web-interface/search/all/v2?keyword=Java",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "https://www.bilibili.com/",
                "https://www.bilibili.com");
        System.out.println("Raw JSON length: " + (json != null ? json.length() : 0));
        System.out.println("Raw JSON preview: " + (json != null ? json.substring(0, Math.min(200, json.length())) : "null"));

        @SuppressWarnings("unchecked")
        List<SearchResourceItem> resources = svc.fetchBilibiliCandidates("Java", "Java", 3);

        Assertions.assertNotNull(resources);
        System.out.println("Bilibili result count: " + resources.size());
        for (SearchResourceItem r : resources) {
            System.out.println("  - " + r.getTitle() + " [" + r.getPlatform() + "] " + r.getUrl());
        }
    }

    @Test
    public void testCrawlWithEmptyQuery() throws Exception {
        BilibiliCrawlerService svc = createMinimalService();

        List<SearchResourceItem> resources = svc.fetchBilibiliCandidates("", "test", 3);
        Assertions.assertTrue(resources.isEmpty());
    }

    @Test
    public void testScheduleTriggerDedupLogic() {
        com.chao.common.dto.DailyPlanCommitResponse resp = new com.chao.common.dto.DailyPlanCommitResponse();
        com.chao.common.dto.TaskScheduleDto s1 = new com.chao.common.dto.TaskScheduleDto();
        s1.setTaskTitle("Spring Boot");
        com.chao.common.dto.TaskScheduleDto s2 = new com.chao.common.dto.TaskScheduleDto();
        s2.setTaskTitle("Redis");
        com.chao.common.dto.TaskScheduleDto s3 = new com.chao.common.dto.TaskScheduleDto();
        s3.setTaskTitle("Spring Boot");
        resp.setSchedules(List.of(s1, s2, s3));

        Set<String> topics = new LinkedHashSet<>();
        for (com.chao.common.dto.TaskScheduleDto s : resp.getSchedules()) {
            if (s.getTaskTitle() != null && !s.getTaskTitle().isBlank()) {
                topics.add(s.getTaskTitle().trim());
            }
        }
        Assertions.assertEquals(2, topics.size());
        Assertions.assertTrue(topics.contains("Spring Boot"));
        Assertions.assertTrue(topics.contains("Redis"));
        System.out.println("Dedup topics: " + topics);
    }

    private BilibiliCrawlerService createMinimalService() throws Exception {
        java.lang.reflect.Constructor<BilibiliCrawlerService> ctor =
                BilibiliCrawlerService.class.getDeclaredConstructor(
                        CourseResourceMapper.class,
                        CourseResourceSearchRepository.class,
                        com.chao.common.client.GoalClient.class,
                        org.springframework.web.client.RestTemplate.class,
                        java.util.concurrent.Executor.class,
                        ObjectMapper.class);
        ctor.setAccessible(true);
        return ctor.newInstance(null, null, null, null, null, objectMapper);
    }
}
