package com.chao.resource.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.client.ResourceClient;
import com.chao.common.dto.SearchResourceItem;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Integration-adjacent tests for multi-platform crawlers.
 * Tests fetchCandidates() against real APIs (network required).
 */
public class MultiPlatformCrawlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void testGitHubCrawl() {
        GitHubCrawlerService svc = createGitHubService();
        List<SearchResourceItem> results = svc.fetchCandidates("Java", "Java", 3);
        Assertions.assertNotNull(results);
        System.out.println("GitHub: " + results.size() + " results");
        for (SearchResourceItem r : results) {
            System.out.println("  - [" + r.getPlatform() + "] " + r.getTitle() + " -> " + r.getUrl());
            Assertions.assertEquals("GitHub", r.getPlatform());
            Assertions.assertNotNull(r.getTitle());
            Assertions.assertTrue(r.getUrl().contains("github.com"));
        }
    }

    @Test
    public void testGitHubCrawlPython() {
        GitHubCrawlerService svc = createGitHubService();
        List<SearchResourceItem> results = svc.fetchCandidates("Python tutorial", "Python", 3);
        Assertions.assertNotNull(results);
        System.out.println("GitHub Python: " + results.size() + " results");
        for (SearchResourceItem r : results) {
            System.out.println("  - " + r.getTitle() + " | Stars: " + r.getSummary());
        }
    }

    @Test
    public void testJuejinCrawl() {
        JuejinCrawlerService svc = createJuejinService();
        List<SearchResourceItem> results = svc.fetchCandidates("Java", "Java", 3);
        Assertions.assertNotNull(results);
        System.out.println("Juejin: " + results.size() + " results");
        for (SearchResourceItem r : results) {
            System.out.println("  - [" + r.getPlatform() + "] " + r.getTitle() + " -> " + r.getUrl());
            if (!results.isEmpty()) {
                Assertions.assertEquals("掘金", r.getPlatform());
                Assertions.assertNotNull(r.getTitle());
            }
        }
    }

    @Test
    public void testImoocCrawl() {
        ImoocCrawlerService svc = createImoocService();
        List<SearchResourceItem> results = svc.fetchCandidates("Java", "Java", 3);
        Assertions.assertNotNull(results);
        System.out.println("Imooc: " + results.size() + " results");
        for (SearchResourceItem r : results) {
            System.out.println("  - [" + r.getPlatform() + "] " + r.getTitle() + " -> " + r.getUrl());
        }
        // Imooc may return empty if page structure changed, that's OK
    }

    @Test
    public void testCsdnCrawl() {
        CsdnCrawlerService svc = createCsdnService();
        List<SearchResourceItem> results = svc.fetchCandidates("Java", "Java", 3);
        Assertions.assertNotNull(results);
        System.out.println("CSDN: " + results.size() + " results");
        for (SearchResourceItem r : results) {
            System.out.println("  - [" + r.getPlatform() + "] " + r.getTitle() + " -> " + r.getUrl());
        }
    }

    @Test
    public void testCnblogsCrawl() {
        CnblogsCrawlerService svc = createCnblogsService();
        List<SearchResourceItem> results = svc.fetchCandidates("Java", "Java", 3);
        Assertions.assertNotNull(results);
        System.out.println("Cnblogs: " + results.size() + " results");
        for (SearchResourceItem r : results) {
            System.out.println("  - [" + r.getPlatform() + "] " + r.getTitle() + " -> " + r.getUrl());
        }
    }

    @Test
    public void testAllPlatformsReturnConsistentFormat() {
        // Verify all platforms return resources with required fields populated
        validateResults("GitHub", createGitHubService().fetchCandidates("Java", "Java", 2));
        validateResults("掘金", createJuejinService().fetchCandidates("Python", "Python", 2));
        validateResults("Cnblogs", createCnblogsService().fetchCandidates("Spring Boot", "Spring Boot", 2));
    }

    private void validateResults(String platform, List<SearchResourceItem> results) {
        System.out.println("Testing " + platform + ": " + results.size() + " results");
        Assertions.assertNotNull(results, platform + " returned null");
        for (SearchResourceItem r : results) {
            Assertions.assertNotNull(r.getTitle(), platform + " title is null");
            Assertions.assertFalse(r.getTitle().isBlank(), platform + " title is blank");
            Assertions.assertNotNull(r.getUrl(), platform + " url is null");
            Assertions.assertFalse(r.getUrl().isBlank(), platform + " url is blank");
            Assertions.assertNotNull(r.getPlatform(), platform + " platform is null");
            Assertions.assertNotNull(r.getSummary(), platform + " summary is null");
        }
    }

    @Test
    public void testCrawlerUtilsIsValidTitle() {
        Assertions.assertTrue(CrawlerUtils.isValidTitle("Spring Boot入门教程", "Java"));
        Assertions.assertTrue(CrawlerUtils.isValidTitle("Python数据分析实战", "Python"));
        Assertions.assertFalse(CrawlerUtils.isValidTitle("", "Java"));
        Assertions.assertFalse(CrawlerUtils.isValidTitle("abc", "Java"));
        Assertions.assertFalse(CrawlerUtils.isValidTitle("12345678", "Java"));
        Assertions.assertFalse(CrawlerUtils.isValidTitle("8b549e65424853c005a4f0ce2a0eac38", "Java"));
    }

    @Test
    public void testCrawlerUtilsIsContentRelevant() {
        Assertions.assertTrue(CrawlerUtils.isContentRelevantToTopic("Java", "Java基础教程", "学习Java编程"));
        Assertions.assertTrue(CrawlerUtils.isContentRelevantToTopic("Python", "Python入门到精通", ""));
        Assertions.assertFalse(CrawlerUtils.isContentRelevantToTopic("机器学习", "奥迪A6L评测", "汽车测评内容"));
    }

    @Test
    public void testCrawlerUtilsBuildSearchQueries() {
        List<String> q1 = CrawlerUtils.buildSearchQueries("Java", "tutorial,guide");
        System.out.println("Java queries: " + q1);
        Assertions.assertTrue(q1.size() >= 3, "Should have at least topic + suffix + year queries");
        Assertions.assertTrue(q1.contains("Java"), "Should contain original topic");
        Assertions.assertTrue(q1.contains("Java tutorial"), "Should contain suffixed query");

        List<String> q2 = CrawlerUtils.buildSearchQueries("机器学习", "入门,教程");
        System.out.println("CJK queries: " + q2);
        Assertions.assertTrue(q2.contains("机器学习入门"));
        Assertions.assertTrue(q2.contains("机器学习教程"));
    }

    // --- helpers ---

    private GitHubCrawlerService createGitHubService() {
        return createService(GitHubCrawlerService.class);
    }

    private JuejinCrawlerService createJuejinService() {
        return createService(JuejinCrawlerService.class);
    }

    private ImoocCrawlerService createImoocService() {
        return createService(ImoocCrawlerService.class);
    }

    private CsdnCrawlerService createCsdnService() {
        return createService(CsdnCrawlerService.class);
    }

    private CnblogsCrawlerService createCnblogsService() {
        return createService(CnblogsCrawlerService.class);
    }

    @SuppressWarnings("unchecked")
    private <T> T createService(Class<T> clazz) {
        try {
            var ctor = clazz.getDeclaredConstructor(
                    com.chao.resource.mapper.CourseResourceMapper.class,
                    com.chao.resource.search.CourseResourceSearchRepository.class,
                    org.springframework.web.client.RestTemplate.class,
                    java.util.concurrent.Executor.class,
                    ObjectMapper.class);
            ctor.setAccessible(true);
            return ctor.newInstance(null, null, null, null, objectMapper);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create " + clazz.getSimpleName(), e);
        }
    }

}
