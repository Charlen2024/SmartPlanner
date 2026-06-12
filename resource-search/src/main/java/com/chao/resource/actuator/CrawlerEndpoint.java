package com.chao.resource.actuator;

import com.chao.resource.service.BilibiliCrawlerService;
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

    private final BilibiliCrawlerService bilibiliCrawlerService;

    @ReadOperation
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", bilibiliCrawlerService.isBilibiliCrawlerEnabled());
        m.put("running", bilibiliCrawlerService.isCrawlerRunning());
        m.put("paused", bilibiliCrawlerService.isCrawlerPaused());
        m.put("totalCrawled", bilibiliCrawlerService.getTotalCrawled());
        m.put("lastRunTopicsCount", bilibiliCrawlerService.getLastRunTopicsCount());
        m.put("lastRunNewCount", bilibiliCrawlerService.getLastRunNewCount());
        m.put("consecutiveFailures", bilibiliCrawlerService.getConsecutiveFailures());
        m.put("consecutiveZeroNew", bilibiliCrawlerService.getConsecutiveZeroNew());
        m.put("intervalMs", bilibiliCrawlerService.getBilibiliCrawlerIntervalMs());
        m.put("topicDelayMs", bilibiliCrawlerService.getBilibiliCrawlerTopicDelayMs());
        m.put("perTopicLimit", bilibiliCrawlerService.getBilibiliCrawlerPerTopicLimit());
        long t = bilibiliCrawlerService.getLastRunTime();
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
        if (!bilibiliCrawlerService.isBilibiliCrawlerEnabled()) {
            m.put("success", false);
            m.put("message", "Crawler is disabled");
            return m;
        }
        if (bilibiliCrawlerService.isCrawlerRunning()) {
            m.put("success", false);
            m.put("message", "Crawler is already running");
            return m;
        }
        new Thread(() -> bilibiliCrawlerService.scheduledBilibiliCrawl()).start();
        m.put("success", true);
        m.put("message", "Crawler triggered");
        return m;
    }

    @WriteOperation
    public Map<String, Object> toggle(@Selector String action) {
        Map<String, Object> m = new LinkedHashMap<>();
        if ("pause".equalsIgnoreCase(action)) {
            bilibiliCrawlerService.pauseCrawler();
            m.put("success", true);
            m.put("message", "Crawler paused");
        } else if ("resume".equalsIgnoreCase(action)) {
            bilibiliCrawlerService.resumeCrawler();
            m.put("success", true);
            m.put("message", "Crawler resumed");
        } else {
            m.put("success", false);
            m.put("message", "Unknown action: " + action + ". Use 'pause' or 'resume'.");
        }
        return m;
    }
}
