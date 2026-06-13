package com.chao.resource.service;

import com.chao.common.client.GoalClient;
import com.chao.common.config.RabbitMqConfig;
import com.chao.common.dto.NotificationMessage;
import com.chao.common.dto.Result;
import com.chao.resource.entity.CourseResource;
import com.chao.resource.mapper.CourseResourceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates all platform crawlers (Bilibili, GitHub, Juejin, Imooc, CSDN, Cnblogs).
 * Provides unified entry points for on-demand and scheduled crawling.
 */
@Slf4j
@Service
public class CrawlerOrchestratorService {

    private final BilibiliCrawlerService bilibiliCrawler;
    private final GitHubCrawlerService githubCrawler;
    private final JuejinCrawlerService juejinCrawler;
    private final ImoocCrawlerService imoocCrawler;
    private final CsdnCrawlerService csdnCrawler;
    private final CnblogsCrawlerService cnblogsCrawler;
    private final CourseResourceMapper courseResourceMapper;
    private final GoalClient goalClient;
    private final Executor aiTaskExecutor;
    private final RabbitTemplate rabbitTemplate;

    public CrawlerOrchestratorService(
            BilibiliCrawlerService bilibiliCrawler,
            GitHubCrawlerService githubCrawler,
            JuejinCrawlerService juejinCrawler,
            ImoocCrawlerService imoocCrawler,
            CsdnCrawlerService csdnCrawler,
            CnblogsCrawlerService cnblogsCrawler,
            CourseResourceMapper courseResourceMapper,
            GoalClient goalClient,
            @Qualifier("aiTaskExecutor") Executor aiTaskExecutor,
            RabbitTemplate rabbitTemplate) {
        this.bilibiliCrawler = bilibiliCrawler;
        this.githubCrawler = githubCrawler;
        this.juejinCrawler = juejinCrawler;
        this.imoocCrawler = imoocCrawler;
        this.csdnCrawler = csdnCrawler;
        this.cnblogsCrawler = cnblogsCrawler;
        this.courseResourceMapper = courseResourceMapper;
        this.goalClient = goalClient;
        this.aiTaskExecutor = aiTaskExecutor;
        this.rabbitTemplate = rabbitTemplate;
    }

    // ---- platform accessors ----

    public BilibiliCrawlerService bilibili() { return bilibiliCrawler; }
    public GitHubCrawlerService github() { return githubCrawler; }
    public JuejinCrawlerService juejin() { return juejinCrawler; }
    public ImoocCrawlerService imooc() { return imoocCrawler; }
    public CsdnCrawlerService csdn() { return csdnCrawler; }
    public CnblogsCrawlerService cnblogs() { return cnblogsCrawler; }

    public List<String> platformNames() {
        List<String> names = new ArrayList<>();
        names.add("B站");
        if (githubCrawler.isEnabled()) names.add("GitHub");
        if (juejinCrawler.isEnabled()) names.add("掘金");
        if (imoocCrawler.isEnabled()) names.add("慕课网");
        if (csdnCrawler.isEnabled()) names.add("CSDN");
        if (cnblogsCrawler.isEnabled()) names.add("博客园");
        return names;
    }

    // ---- on-demand crawl (triggered by goal creation) ----

    /**
     * Fire-and-forget without notification (used when userId is unavailable, e.g. search fallback).
     */
    public void crawlTopicAsync(String topic) {
        crawlTopicAsync(topic, null);
    }

    /**
     * Triggers all enabled platform crawlers for the given topic.
     * Sends SSE notification to the user when all crawlers complete (if userId is non-null).
     */
    public void crawlTopicAsync(String topic, Long userId) {
        if (topic == null || topic.isBlank()) return;
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        futures.add(bilibiliCrawler.crawlTopicAsync(topic));

        if (githubCrawler.isEnabled())
            futures.add(githubCrawler.crawlTopicAsync(topic));
        if (juejinCrawler.isEnabled())
            futures.add(juejinCrawler.crawlTopicAsync(topic));
        if (imoocCrawler.isEnabled())
            futures.add(imoocCrawler.crawlTopicAsync(topic));
        if (csdnCrawler.isEnabled())
            futures.add(csdnCrawler.crawlTopicAsync(topic));
        if (cnblogsCrawler.isEnabled())
            futures.add(cnblogsCrawler.crawlTopicAsync(topic));

        log.info("Orchestrator: starting on-demand crawl for '{}' across {} platforms", topic, futures.size());
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(300, TimeUnit.SECONDS)
                .thenRunAsync(() -> sendCrawlCompleteNotification(topic, userId), aiTaskExecutor)
                .exceptionally(ex -> { log.warn("On-demand crawl for '{}' timed out or failed", topic); return null; });
    }

    private void sendCrawlCompleteNotification(String topic, Long userId) {
        if (userId == null) return;
        try {
            NotificationMessage notif = new NotificationMessage();
            notif.setUserId(userId);
            notif.setType("CRAWL_COMPLETED");
            notif.setContent("「" + topic + "」相关资源爬取完成");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("topic", topic);
            payload.put("level", "success");
            notif.setPayload(payload);
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.NOTIFICATION_EXCHANGE,
                    RabbitMqConfig.NOTIFICATION_ROUTING_KEY, notif);
            log.info("Sent CRAWL_COMPLETED notification for topic '{}' to userId={}", topic, userId);
        } catch (Exception e) {
            log.warn("Failed to send crawl complete notification for '{}': {}", topic, e.getMessage());
        }
    }

    // ---- scheduled crawl ----

    /**
     * Collect topics from all sources and run scheduled crawls across all platforms.
     */
    public void scheduledCrawlAll() {
        Set<String> topics = collectTopics();
        if (topics.isEmpty()) {
            log.info("Orchestrator: no topics to crawl");
            return;
        }
        List<String> topicList = new ArrayList<>(topics);
        log.info("Orchestrator: starting scheduled crawl for {} topics across {}", topicList.size(), platformNames());

        // Bilibili has its own scheduler, but we can also trigger it here
        // Run all platforms in parallel
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        futures.add(CompletableFuture.runAsync(() -> bilibiliCrawler.scheduledBilibiliCrawl(), aiTaskExecutor));
        futures.add(CompletableFuture.runAsync(() -> githubCrawler.scheduledCrawl(topicList), aiTaskExecutor));
        futures.add(CompletableFuture.runAsync(() -> juejinCrawler.scheduledCrawl(topicList), aiTaskExecutor));
        futures.add(CompletableFuture.runAsync(() -> imoocCrawler.scheduledCrawl(topicList), aiTaskExecutor));
        futures.add(CompletableFuture.runAsync(() -> csdnCrawler.scheduledCrawl(topicList), aiTaskExecutor));
        futures.add(CompletableFuture.runAsync(() -> cnblogsCrawler.scheduledCrawl(topicList), aiTaskExecutor));

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(600, TimeUnit.SECONDS)
                .exceptionally(ex -> { log.warn("Scheduled crawl timed out"); return null; });
    }

    // ---- statistics aggregation ----

    public long getTotalCrawled() {
        return bilibiliCrawler.getTotalCrawled()
                + githubCrawler.getTotalCrawled()
                + juejinCrawler.getTotalCrawled()
                + imoocCrawler.getTotalCrawled()
                + csdnCrawler.getTotalCrawled()
                + cnblogsCrawler.getTotalCrawled();
    }

    public boolean isAnyRunning() {
        return bilibiliCrawler.isCrawlerRunning()
                || githubCrawler.isRunning()
                || juejinCrawler.isRunning()
                || imoocCrawler.isRunning()
                || csdnCrawler.isRunning()
                || cnblogsCrawler.isRunning();
    }

    // ---- topic collection ----

    private Set<String> collectTopics() {
        Set<String> topics = new LinkedHashSet<>();
        // 1. From existing DB resources
        try {
            List<CourseResource> existing = courseResourceMapper.selectList(null);
            if (existing != null) {
                for (CourseResource r : existing) {
                    if (r.getTopic() != null && !r.getTopic().isBlank()) {
                        topics.add(r.getTopic().trim());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load existing topics from DB: {}", e.getMessage());
        }
        // 2. From user goals
        try {
            Result<List<String>> result = goalClient.getDistinctTopics();
            List<String> goalTopics = result != null ? result.getData() : List.of();
            if (goalTopics != null) {
                for (String t : goalTopics) {
                    if (t != null && !t.isBlank()) topics.add(t.trim());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get goal topics: {}", e.getMessage());
        }
        return topics;
    }
}
