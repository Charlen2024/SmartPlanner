package com.chao.resource.actuator;

import com.chao.resource.service.CrawlerOrchestratorService;
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
import java.util.List;
import java.util.Map;

@Component
@Endpoint(id = "crawler")
@RequiredArgsConstructor
public class CrawlerEndpoint {

    private final CrawlerOrchestratorService orchestrator;

    @ReadOperation
    public Map<String, Object> status() {
        var b = orchestrator.bilibili();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("platforms", orchestrator.platformNames());
        m.put("anyRunning", orchestrator.isAnyRunning());
        m.put("totalCrawled", orchestrator.getTotalCrawled());

        // Per-platform stats
        Map<String, Object> byPlatform = new LinkedHashMap<>();
        byPlatform.put("B站", statsMap(b.isBilibiliCrawlerEnabled(), b.isCrawlerRunning(), b.isCrawlerPaused(),
                b.getTotalCrawled(), b.getLastRunTopicsCount(), b.getLastRunNewCount(),
                b.getConsecutiveFailures(), b.getConsecutiveZeroNew(), b.getLastRunTime()));
        byPlatform.put("GitHub", statsMap(orchestrator.github().isEnabled(), orchestrator.github().isRunning(), false,
                orchestrator.github().getTotalCrawled(), orchestrator.github().getLastRunTopicsCount(),
                orchestrator.github().getLastRunNewCount(), orchestrator.github().getConsecutiveFailures(),
                orchestrator.github().getConsecutiveZeroNew(), orchestrator.github().getLastRunTime()));
        byPlatform.put("掘金", statsMap(orchestrator.juejin().isEnabled(), orchestrator.juejin().isRunning(), false,
                orchestrator.juejin().getTotalCrawled(), orchestrator.juejin().getLastRunTopicsCount(),
                orchestrator.juejin().getLastRunNewCount(), orchestrator.juejin().getConsecutiveFailures(),
                orchestrator.juejin().getConsecutiveZeroNew(), orchestrator.juejin().getLastRunTime()));
        byPlatform.put("慕课网", statsMap(orchestrator.imooc().isEnabled(), orchestrator.imooc().isRunning(), false,
                orchestrator.imooc().getTotalCrawled(), orchestrator.imooc().getLastRunTopicsCount(),
                orchestrator.imooc().getLastRunNewCount(), orchestrator.imooc().getConsecutiveFailures(),
                orchestrator.imooc().getConsecutiveZeroNew(), orchestrator.imooc().getLastRunTime()));
        byPlatform.put("CSDN", statsMap(orchestrator.csdn().isEnabled(), orchestrator.csdn().isRunning(), false,
                orchestrator.csdn().getTotalCrawled(), orchestrator.csdn().getLastRunTopicsCount(),
                orchestrator.csdn().getLastRunNewCount(), orchestrator.csdn().getConsecutiveFailures(),
                orchestrator.csdn().getConsecutiveZeroNew(), orchestrator.csdn().getLastRunTime()));
        byPlatform.put("博客园", statsMap(orchestrator.cnblogs().isEnabled(), orchestrator.cnblogs().isRunning(), false,
                orchestrator.cnblogs().getTotalCrawled(), orchestrator.cnblogs().getLastRunTopicsCount(),
                orchestrator.cnblogs().getLastRunNewCount(), orchestrator.cnblogs().getConsecutiveFailures(),
                orchestrator.cnblogs().getConsecutiveZeroNew(), orchestrator.cnblogs().getLastRunTime()));
        m.put("byPlatform", byPlatform);
        return m;
    }

    private Map<String, Object> statsMap(boolean enabled, boolean running, boolean paused,
                                          long total, int topics, int newCount, int fails, int zeroNew, long lastRun) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("running", running);
        if (paused) m.put("paused", true);
        m.put("totalCrawled", total);
        m.put("lastRunTopicsCount", topics);
        m.put("lastRunNewCount", newCount);
        m.put("consecutiveFailures", fails);
        m.put("consecutiveZeroNew", zeroNew);
        if (lastRun > 0) {
            m.put("lastRunTime", DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(lastRun)));
        }
        return m;
    }

    @WriteOperation
    public Map<String, Object> trigger() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (orchestrator.isAnyRunning()) {
            m.put("success", false);
            m.put("message", "A crawler is already running");
            return m;
        }
        new Thread(() -> orchestrator.scheduledCrawlAll()).start();
        m.put("success", true);
        m.put("message", "All crawlers triggered across " + orchestrator.platformNames().size() + " platforms");
        return m;
    }

    @WriteOperation
    public Map<String, Object> toggle(@Selector String action) {
        Map<String, Object> m = new LinkedHashMap<>();
        if ("pause".equalsIgnoreCase(action)) {
            orchestrator.bilibili().pauseCrawler();
            m.put("success", true);
            m.put("message", "Bilibili crawler paused (other platforms continue)");
        } else if ("resume".equalsIgnoreCase(action)) {
            orchestrator.bilibili().resumeCrawler();
            m.put("success", true);
            m.put("message", "Bilibili crawler resumed");
        } else {
            m.put("success", false);
            m.put("message", "Unknown action: " + action + ". Use 'pause' or 'resume'.");
        }
        return m;
    }
}
