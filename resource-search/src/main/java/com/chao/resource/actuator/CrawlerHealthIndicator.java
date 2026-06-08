package com.chao.resource.actuator;

import com.chao.resource.service.ResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CrawlerHealthIndicator implements HealthIndicator {

    private final ResourceService resourceService;

    @Override
    public Health health() {
        if (!resourceService.isBilibiliCrawlerEnabled()) {
            return Health.unknown()
                    .withDetail("reason", "Crawler is disabled")
                    .build();
        }

        int failures = resourceService.getConsecutiveFailures();
        if (failures >= 3) {
            return Health.down()
                    .withDetail("reason", failures + " consecutive crawl failures")
                    .withDetail("consecutiveFailures", failures)
                    .withDetail("totalCrawled", resourceService.getTotalCrawled())
                    .build();
        }

        int zeroNew = resourceService.getConsecutiveZeroNew();
        if (zeroNew >= 2) {
            return Health.outOfService()
                    .withDetail("reason", zeroNew + " consecutive runs with 0 new resources")
                    .withDetail("consecutiveZeroNew", zeroNew)
                    .withDetail("totalCrawled", resourceService.getTotalCrawled())
                    .build();
        }

        Health.Builder builder = Health.up()
                .withDetail("totalCrawled", resourceService.getTotalCrawled())
                .withDetail("lastRunNewCount", resourceService.getLastRunNewCount())
                .withDetail("lastRunTopicsCount", resourceService.getLastRunTopicsCount());

        if (resourceService.isCrawlerPaused()) {
            builder.withDetail("paused", true);
        }

        return builder.build();
    }
}
