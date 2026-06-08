package com.chao.resource.actuator;

import com.chao.resource.service.ResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Endpoint(id = "crawler")
@RequiredArgsConstructor
public class CrawlerEndpoint {

    private final ResourceService resourceService;

    @ReadOperation
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", resourceService.isBilibiliCrawlerEnabled());
        m.put("running", resourceService.isCrawlerRunning());
        m.put("paused", resourceService.isCrawlerPaused());
        m.put("totalCrawled", resourceService.getTotalCrawled());
        m.put("lastRunTopicsCount", resourceService.getLastRunTopicsCount());
        m.put("lastRunNewCount", resourceService.getLastRunNewCount());
        m.put("consecutiveFailures", resourceService.getConsecutiveFailures());
        m.put("consecutiveZeroNew", resourceService.getConsecutiveZeroNew());
        m.put("intervalMs", resourceService.getBilibiliCrawlerIntervalMs());
        m.put("topicDelayMs", resourceService.getBilibiliCrawlerTopicDelayMs());
        m.put("perTopicLimit", resourceService.getBilibiliCrawlerPerTopicLimit());
        long t = resourceService.getLastRunTime();
        if (t > 0) {
            m.put("lastRunTime", DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(t)));
        } else {
            m.put("lastRunTime", null);
        }
        return m;
    }

    @WriteOperation
    public Map<String, Object> trigger() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!resourceService.isBilibiliCrawlerEnabled()) {
            m.put("success", false);
            m.put("message", "Crawler is disabled");
            return m;
        }
        if (resourceService.isCrawlerRunning()) {
            m.put("success", false);
            m.put("message", "Crawler is already running");
            return m;
        }
        new Thread(() -> resourceService.scheduledBilibiliCrawl()).start();
        m.put("success", true);
        m.put("message", "Crawler triggered");
        return m;
    }

    @WriteOperation
    public Map<String, Object> toggle(@Selector String action) {
        Map<String, Object> m = new LinkedHashMap<>();
        if ("pause".equalsIgnoreCase(action)) {
            resourceService.pauseCrawler();
            m.put("success", true);
            m.put("message", "Crawler paused");
        } else if ("resume".equalsIgnoreCase(action)) {
            resourceService.resumeCrawler();
            m.put("success", true);
            m.put("message", "Crawler resumed");
        } else {
            m.put("success", false);
            m.put("message", "Unknown action: " + action + ". Use 'pause' or 'resume'.");
        }
        return m;
    }
}
