package com.chao.resource.actuator;

import com.chao.resource.service.BilibiliCrawlerService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CrawlerHealthIndicator implements HealthIndicator {

    private final BilibiliCrawlerService bilibiliCrawlerService;

    @Override
    public Health health() {
        if (!bilibiliCrawlerService.isBilibiliCrawlerEnabled()) {
            return Health.unknown()
                    .withDetail("reason", "Crawler is disabled")
                    .build();
        }

        int failures = bilibiliCrawlerService.getConsecutiveFailures();
        if (failures >= 3) {
            return Health.down()
                    .withDetail("reason", failures + " consecutive crawl failures")
                    .withDetail("consecutiveFailures", failures)
                    .withDetail("totalCrawled", bilibiliCrawlerService.getTotalCrawled())
                    .build();
        }

        int zeroNew = bilibiliCrawlerService.getConsecutiveZeroNew();
        if (zeroNew >= 2) {
            return Health.outOfService()
                    .withDetail("reason", zeroNew + " consecutive runs with 0 new resources")
                    .withDetail("consecutiveZeroNew", zeroNew)
                    .withDetail("totalCrawled", bilibiliCrawlerService.getTotalCrawled())
                    .build();
        }

        Health.Builder builder = Health.up()
                .withDetail("totalCrawled", bilibiliCrawlerService.getTotalCrawled())
                .withDetail("lastRunNewCount", bilibiliCrawlerService.getLastRunNewCount())
                .withDetail("lastRunTopicsCount", bilibiliCrawlerService.getLastRunTopicsCount());

        if (bilibiliCrawlerService.isCrawlerPaused()) {
            builder.withDetail("paused", true);
        }

        return builder.build();
    }
}
