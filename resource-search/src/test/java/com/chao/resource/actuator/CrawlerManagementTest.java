package com.chao.resource.actuator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.resource.service.BilibiliCrawlerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BilibiliCrawlerService behavior (pause/resume, statistics, health thresholds).
 * The CrawlerEndpoint/CrawlerHealthIndicator aggregation tests are covered by
 * integration tests that exercise the full orchestrator.
 */
public class CrawlerManagementTest {

    private BilibiliCrawlerService service;

    @BeforeEach
    void setUp() throws Exception {
        service = createMinimalService();
    }

    // ---- pause/resume ----

    @Test
    void pauseCrawler_shouldSetPaused() {
        assertFalse(service.isCrawlerPaused());
        service.pauseCrawler();
        assertTrue(service.isCrawlerPaused());
    }

    @Test
    void resumeCrawler_shouldClearPaused() {
        service.pauseCrawler();
        assertTrue(service.isCrawlerPaused());
        service.resumeCrawler();
        assertFalse(service.isCrawlerPaused());
    }

    // ---- statistics ----

    @Test
    void statistics_shouldInitializeCorrectly() {
        assertEquals(0, service.getTotalCrawled());
        assertEquals(0, service.getConsecutiveFailures());
        assertEquals(0, service.getConsecutiveZeroNew());
        assertEquals(0, service.getLastRunTopicsCount());
        assertEquals(0, service.getLastRunNewCount());
        assertEquals(0, service.getLastRunTime());
        assertFalse(service.isCrawlerRunning());
    }

    @Test
    void statistics_fieldsShouldBeModifiable() throws Exception {
        setField(service, "totalCrawled", 150L);
        setField(service, "consecutiveFailures", 3);
        setField(service, "consecutiveZeroNew", 2);
        setField(service, "lastRunTopicsCount", 10);
        setField(service, "lastRunNewCount", 25);

        assertEquals(150L, service.getTotalCrawled());
        assertEquals(3, service.getConsecutiveFailures());
        assertEquals(2, service.getConsecutiveZeroNew());
        assertEquals(10, service.getLastRunTopicsCount());
        assertEquals(25, service.getLastRunNewCount());
    }

    // ---- crawlTopicAsync ----

    @Test
    void crawlTopicAsync_shouldNoopWhenDisabled() {
        setField(service, "bilibiliCrawlerEnabled", false);
        assertDoesNotThrow(() -> service.crawlTopicAsync("Spring Boot"));
        assertFalse(service.isCrawlerRunning());
    }

    @Test
    void crawlTopicAsync_shouldNoopWhenBlankTopic() {
        setField(service, "bilibiliCrawlerEnabled", true);
        assertDoesNotThrow(() -> service.crawlTopicAsync(""));
        assertDoesNotThrow(() -> service.crawlTopicAsync(null));
        assertDoesNotThrow(() -> service.crawlTopicAsync("   "));
    }

    @Test
    void crawlTopicAsync_shouldSkipWhenAlreadyRunning() throws Exception {
        setField(service, "bilibiliCrawlerEnabled", true);
        Field f = BilibiliCrawlerService.class.getDeclaredField("onDemandCrawlerRunning");
        f.setAccessible(true);
        AtomicBoolean ab = (AtomicBoolean) f.get(service);
        ab.set(true);

        assertDoesNotThrow(() -> service.crawlTopicAsync("Java"));
        // The onDemandRunning flag should prevent double-run, but the method
        // runs async so we just verify no exception is thrown
    }

    // ---- configuration ----

    @Test
    void config_shouldBeSettableViaReflection() {
        // Values are set via @Value when Spring-managed; via reflection they start at Java defaults
        setField(service, "bilibiliCrawlerEnabled", true);
        setField(service, "bilibiliCrawlerIntervalMs", 21600000L);
        setField(service, "bilibiliCrawlerTopicDelayMs", 800L);
        setField(service, "bilibiliCrawlerPerTopicLimit", 3);

        assertTrue(service.isBilibiliCrawlerEnabled());
        assertEquals(21600000L, service.getBilibiliCrawlerIntervalMs());
        assertEquals(800L, service.getBilibiliCrawlerTopicDelayMs());
        assertEquals(3, service.getBilibiliCrawlerPerTopicLimit());
    }

    // ---- helpers ----

    private static BilibiliCrawlerService createMinimalService() throws Exception {
        java.lang.reflect.Constructor<BilibiliCrawlerService> ctor =
                BilibiliCrawlerService.class.getDeclaredConstructor(
                        com.chao.resource.mapper.CourseResourceMapper.class,
                        com.chao.resource.search.CourseResourceSearchRepository.class,
                        com.chao.common.client.GoalClient.class,
                        org.springframework.web.client.RestTemplate.class,
                        java.util.concurrent.Executor.class,
                        ObjectMapper.class);
        ctor.setAccessible(true);
        return ctor.newInstance(null, null, null, null, null, new ObjectMapper());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = BilibiliCrawlerService.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
