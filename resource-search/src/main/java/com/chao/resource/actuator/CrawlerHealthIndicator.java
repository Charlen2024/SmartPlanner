package com.chao.resource.actuator;

import com.chao.resource.service.CrawlerOrchestratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CrawlerHealthIndicator implements HealthIndicator {

    private final CrawlerOrchestratorService orchestrator;

    @Override
    public Health health() {
        // Aggregate health across all platforms
        int totalFailures = orchestrator.bilibili().getConsecutiveFailures()
                + orchestrator.github().getConsecutiveFailures()
                + orchestrator.juejin().getConsecutiveFailures()
                + orchestrator.imooc().getConsecutiveFailures()
                + orchestrator.csdn().getConsecutiveFailures()
                + orchestrator.cnblogs().getConsecutiveFailures();

        // If ALL enabled platforms have failures, that's DOWN
        int enabledCount = orchestrator.platformNames().size();
        int failedPlatforms = 0;
        if (orchestrator.bilibili().isBilibiliCrawlerEnabled() && orchestrator.bilibili().getConsecutiveFailures() >= 3) failedPlatforms++;
        if (orchestrator.github().isEnabled() && orchestrator.github().getConsecutiveFailures() >= 3) failedPlatforms++;
        if (orchestrator.juejin().isEnabled() && orchestrator.juejin().getConsecutiveFailures() >= 3) failedPlatforms++;
        if (orchestrator.imooc().isEnabled() && orchestrator.imooc().getConsecutiveFailures() >= 3) failedPlatforms++;
        if (orchestrator.csdn().isEnabled() && orchestrator.csdn().getConsecutiveFailures() >= 3) failedPlatforms++;
        if (orchestrator.cnblogs().isEnabled() && orchestrator.cnblogs().getConsecutiveFailures() >= 3) failedPlatforms++;

        if (enabledCount > 0 && failedPlatforms == enabledCount) {
            return Health.down()
                    .withDetail("reason", "All " + enabledCount + " platforms have consecutive failures")
                    .withDetail("totalCrawled", orchestrator.getTotalCrawled())
                    .withDetail("failedPlatforms", failedPlatforms)
                    .build();
        }

        // OUT_OF_SERVICE if most platforms have zero-new
        int totalZeroNew = orchestrator.bilibili().getConsecutiveZeroNew()
                + orchestrator.github().getConsecutiveZeroNew()
                + orchestrator.juejin().getConsecutiveZeroNew()
                + orchestrator.imooc().getConsecutiveZeroNew()
                + orchestrator.csdn().getConsecutiveZeroNew()
                + orchestrator.cnblogs().getConsecutiveZeroNew();

        if (totalZeroNew >= 6 && totalFailures == 0) {
            return Health.outOfService()
                    .withDetail("reason", "Multiple platforms returning 0 new resources")
                    .withDetail("totalCrawled", orchestrator.getTotalCrawled())
                    .build();
        }

        Health.Builder builder = Health.up()
                .withDetail("totalCrawled", orchestrator.getTotalCrawled())
                .withDetail("platforms", orchestrator.platformNames())
                .withDetail("anyRunning", orchestrator.isAnyRunning());

        if (orchestrator.bilibili().isCrawlerPaused()) {
            builder.withDetail("bilibiliPaused", true);
        }

        return builder.build();
    }
}
