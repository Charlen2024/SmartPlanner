package com.chao.resource.actuator;

import com.chao.resource.service.BilibiliCrawlerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CrawlerManagementTest {

    private BilibiliCrawlerService service;

    @BeforeEach
    void setUp() throws Exception {
        service = createMinimalService();
    }

    // ---- CrawlerEndpoint tests ----

    @Test
    void endpointStatus_shouldReturnCrawlerStats() {
        setField(service, "bilibiliCrawlerEnabled", true);
        setField(service, "lastRunTime", 1700000000000L);
        setField(service, "lastRunTopicsCount", 5);
        setField(service, "lastRunNewCount", 12);
        setField(service, "totalCrawled", 150L);
        setField(service, "consecutiveFailures", 0);
        setField(service, "consecutiveZeroNew", 1);
        setField(service, "crawlerPaused", false);

        CrawlerEndpoint endpoint = new CrawlerEndpoint(service);
        Map<String, Object> status = endpoint.status();

        assertEquals(true, status.get("enabled"));
        assertEquals(false, status.get("paused"));
        assertEquals(150L, status.get("totalCrawled"));
        assertEquals(5, status.get("lastRunTopicsCount"));
        assertEquals(12, status.get("lastRunNewCount"));
        assertEquals(0, status.get("consecutiveFailures"));
        assertEquals(1, status.get("consecutiveZeroNew"));
        assertNotNull(status.get("lastRunTime"));
    }

    @Test
    void endpointStatus_shouldShowNullLastRunTimeWhenZero() {
        setField(service, "lastRunTime", 0L);
        CrawlerEndpoint endpoint = new CrawlerEndpoint(service);
        Map<String, Object> status = endpoint.status();
        assertNull(status.get("lastRunTime"));
    }

    @Test
    void endpointTrigger_shouldFailWhenDisabled() {
        setField(service, "bilibiliCrawlerEnabled", false);
        CrawlerEndpoint endpoint = new CrawlerEndpoint(service);
        Map<String, Object> result = endpoint.trigger();
        assertEquals(false, result.get("success"));
        assertTrue(((String) result.get("message")).contains("disabled"));
    }

    @Test
    void endpointTrigger_shouldFailWhenAlreadyRunning() {
        setField(service, "bilibiliCrawlerEnabled", true);
        try {
            Field f = BilibiliCrawlerService.class.getDeclaredField("crawlerRunning");
            f.setAccessible(true);
            java.util.concurrent.atomic.AtomicBoolean ab = (java.util.concurrent.atomic.AtomicBoolean) f.get(service);
            ab.set(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        CrawlerEndpoint endpoint = new CrawlerEndpoint(service);
        Map<String, Object> result = endpoint.trigger();
        assertEquals(false, result.get("success"));
        assertTrue(((String) result.get("message")).contains("already running"));
    }

    @Test
    void endpointToggle_pause() {
        setField(service, "crawlerPaused", false);
        CrawlerEndpoint endpoint = new CrawlerEndpoint(service);
        Map<String, Object> result = endpoint.toggle("pause");
        assertEquals(true, result.get("success"));
        assertEquals("Crawler paused", result.get("message"));
        assertTrue(service.isCrawlerPaused());
    }

    @Test
    void endpointToggle_resume() {
        setField(service, "crawlerPaused", true);
        CrawlerEndpoint endpoint = new CrawlerEndpoint(service);
        Map<String, Object> result = endpoint.toggle("resume");
        assertEquals(true, result.get("success"));
        assertEquals("Crawler resumed", result.get("message"));
        assertFalse(service.isCrawlerPaused());
    }

    @Test
    void endpointToggle_unknownAction() {
        CrawlerEndpoint endpoint = new CrawlerEndpoint(service);
        Map<String, Object> result = endpoint.toggle("stop");
        assertEquals(false, result.get("success"));
        assertTrue(((String) result.get("message")).contains("Unknown action"));
    }

    // ---- CrawlerHealthIndicator tests ----

    @Test
    void healthIndicator_upWhenHealthy() {
        setField(service, "bilibiliCrawlerEnabled", true);
        setField(service, "consecutiveFailures", 0);
        setField(service, "consecutiveZeroNew", 0);
        setField(service, "totalCrawled", 200L);
        setField(service, "lastRunNewCount", 8);
        setField(service, "lastRunTopicsCount", 10);

        CrawlerHealthIndicator indicator = new CrawlerHealthIndicator(service);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(200L, health.getDetails().get("totalCrawled"));
        assertEquals(8, health.getDetails().get("lastRunNewCount"));
        assertEquals(10, health.getDetails().get("lastRunTopicsCount"));
    }

    @Test
    void healthIndicator_unknownWhenDisabled() {
        setField(service, "bilibiliCrawlerEnabled", false);

        CrawlerHealthIndicator indicator = new CrawlerHealthIndicator(service);
        Health health = indicator.health();

        assertEquals(Status.UNKNOWN, health.getStatus());
        assertEquals("Crawler is disabled", health.getDetails().get("reason"));
    }

    @Test
    void healthIndicator_downOnConsecutiveFailures() {
        setField(service, "bilibiliCrawlerEnabled", true);
        setField(service, "consecutiveFailures", 3);
        setField(service, "consecutiveZeroNew", 0);
        setField(service, "totalCrawled", 100L);

        CrawlerHealthIndicator indicator = new CrawlerHealthIndicator(service);
        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(((String) health.getDetails().get("reason")).contains("consecutive crawl failures"));
        assertEquals(3, health.getDetails().get("consecutiveFailures"));
    }

    @Test
    void healthIndicator_downOnMoreFailuresBeyondThreshold() {
        setField(service, "bilibiliCrawlerEnabled", true);
        setField(service, "consecutiveFailures", 5);
        setField(service, "consecutiveZeroNew", 0);
        setField(service, "totalCrawled", 50L);

        CrawlerHealthIndicator indicator = new CrawlerHealthIndicator(service);
        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(((String) health.getDetails().get("reason")).contains("5 consecutive crawl failures"));
    }

    @Test
    void healthIndicator_outOfServiceOnZeroNew() {
        setField(service, "bilibiliCrawlerEnabled", true);
        setField(service, "consecutiveFailures", 0);
        setField(service, "consecutiveZeroNew", 2);
        setField(service, "totalCrawled", 300L);

        CrawlerHealthIndicator indicator = new CrawlerHealthIndicator(service);
        Health health = indicator.health();

        assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
        assertTrue(((String) health.getDetails().get("reason")).contains("0 new resources"));
        assertEquals(2, health.getDetails().get("consecutiveZeroNew"));
    }

    @Test
    void healthIndicator_upWhenPausedButHealthy() {
        setField(service, "bilibiliCrawlerEnabled", true);
        setField(service, "consecutiveFailures", 0);
        setField(service, "consecutiveZeroNew", 0);
        setField(service, "totalCrawled", 10L);
        setField(service, "crawlerPaused", true);

        CrawlerHealthIndicator indicator = new CrawlerHealthIndicator(service);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(true, health.getDetails().get("paused"));
    }

    // ---- crawlTopicAsync tests ----

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
    void crawlTopicAsync_shouldNotRunWhenAlreadyRunning() throws Exception {
        setField(service, "bilibiliCrawlerEnabled", true);
        Field f = BilibiliCrawlerService.class.getDeclaredField("crawlerRunning");
        f.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean ab = (java.util.concurrent.atomic.AtomicBoolean) f.get(service);
        ab.set(true);

        assertDoesNotThrow(() -> service.crawlTopicAsync("Java"));
        assertTrue(service.isCrawlerRunning());
    }

    // ---- per-topic failure -> health verification ----

    @Test
    void healthIndicator_downWhenConsecutiveFailuresSet() {
        setField(service, "bilibiliCrawlerEnabled", true);
        setField(service, "consecutiveFailures", 4);
        setField(service, "consecutiveZeroNew", 0);
        setField(service, "totalCrawled", 0L);

        CrawlerHealthIndicator indicator = new CrawlerHealthIndicator(service);
        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(((String) health.getDetails().get("reason")).contains("4 consecutive crawl failures"));
    }

    @Test
    void healthIndicator_zeroNewOnlyWhenNoFailures() {
        setField(service, "bilibiliCrawlerEnabled", true);
        setField(service, "consecutiveFailures", 0);
        setField(service, "consecutiveZeroNew", 3);
        setField(service, "totalCrawled", 50L);

        CrawlerHealthIndicator indicator = new CrawlerHealthIndicator(service);
        Health health = indicator.health();

        assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    }

    // ---- helper to verify endpoint status includes crawler config ----

    @Test
    void endpointStatus_shouldIncludeTopicDelayAndLimit() {
        setField(service, "bilibiliCrawlerEnabled", true);
        setField(service, "bilibiliCrawlerTopicDelayMs", 500L);
        setField(service, "bilibiliCrawlerPerTopicLimit", 5);

        CrawlerEndpoint endpoint = new CrawlerEndpoint(service);
        Map<String, Object> status = endpoint.status();

        assertEquals(500L, status.get("topicDelayMs"));
        assertEquals(5, status.get("perTopicLimit"));
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
                        com.fasterxml.jackson.databind.ObjectMapper.class);
        ctor.setAccessible(true);
        return ctor.newInstance(null, null, null, null, null, new com.fasterxml.jackson.databind.ObjectMapper());
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
