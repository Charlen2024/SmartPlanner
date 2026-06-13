package com.chao.resource.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.client.ResourceClient;
import com.chao.resource.mapper.CourseResourceMapper;
import com.chao.resource.search.CourseResourceSearchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GitHub repository crawler using the public REST API (no auth required).
 * Searches for educational repositories by topic.
 */
@Slf4j
@Service
public class GitHubCrawlerService {

    private final CourseResourceMapper courseResourceMapper;
    private final CourseResourceSearchRepository searchRepository;
    private final RestTemplate externalRestTemplate;
    private final Executor aiTaskExecutor;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean onDemandRunning = new AtomicBoolean(false);

    @Value("${smartplanner.crawler.github.enabled:true}")
    private boolean enabled;
    @Value("${smartplanner.crawler.github.per-topic-limit:5}")
    private int perTopicLimit;
    @Value("${smartplanner.crawler.github.query-suffixes:tutorial,guide,project,examples}")
    private String querySuffixes;
    @Value("${smartplanner.crawler.quality-filter.enabled:true}")
    private boolean qualityFilterEnabled;

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    // statistics
    private volatile long lastRunTime;
    private volatile int lastRunTopicsCount;
    private volatile int lastRunNewCount;
    private volatile long totalCrawled;
    private volatile int consecutiveFailures;
    private volatile int consecutiveZeroNew;

    public GitHubCrawlerService(
            CourseResourceMapper courseResourceMapper,
            CourseResourceSearchRepository searchRepository,
            RestTemplate externalRestTemplate,
            @Qualifier("aiTaskExecutor") Executor aiTaskExecutor,
            ObjectMapper objectMapper) {
        this.courseResourceMapper = courseResourceMapper;
        this.searchRepository = searchRepository;
        this.externalRestTemplate = externalRestTemplate;
        this.aiTaskExecutor = aiTaskExecutor;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() { return enabled; }
    public boolean isRunning() { return running.get(); }
    public long getLastRunTime() { return lastRunTime; }
    public int getLastRunTopicsCount() { return lastRunTopicsCount; }
    public int getLastRunNewCount() { return lastRunNewCount; }
    public long getTotalCrawled() { return totalCrawled; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public int getConsecutiveZeroNew() { return consecutiveZeroNew; }

    // ---- public API ----

    public List<ResourceClient.CourseResource> fetchCandidates(String query, String topic, int limit) {
        List<ResourceClient.CourseResource> out = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        List<String> queries = CrawlerUtils.buildSearchQueries(query, querySuffixes);

        for (int qi = 0; qi < queries.size(); qi++) {
            String q = queries.get(qi).trim();
            if (q.isEmpty()) continue;
            if (qi > 0) CrawlerUtils.sleep(800); // rate limit courtesy

            String apiUrl = "https://api.github.com/search/repositories?q="
                    + URLEncoder.encode(q, StandardCharsets.UTF_8)
                    + "&sort=stars&order=desc&per_page=" + Math.min(limit, 10);
            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", "application/vnd.github+json");
            headers.set("User-Agent", "SmartPlanner/1.0");
            headers.set("X-GitHub-Api-Version", "2022-11-28");

            String json = CrawlerUtils.httpGet(externalRestTemplate, apiUrl, headers, 2);
            if (json == null || json.isBlank()) {
                log.debug("GitHub query '{}' returned empty", q);
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(json);
                JsonNode items = root.path("items");
                if (!items.isArray()) continue;
                int added = 0;
                for (JsonNode item : items) {
                    if (out.size() >= limit * 3) break;
                    String fullName = item.path("full_name").asText();
                    String title = item.path("name").asText();
                    String desc = item.path("description").asText("");
                    int stars = item.path("stargazers_count").asInt();
                    String htmlUrl = item.path("html_url").asText();
                    String language = item.path("language").asText("");

                    if (title.isBlank() || htmlUrl.isBlank()) continue;
                    String canonical = ResourceService.canonicalUrl(htmlUrl);
                    if (canonical != null && !seenUrls.add(canonical)) continue;

                    StringBuilder summary = new StringBuilder();
                    summary.append("GitHub: ").append(fullName);
                    summary.append(" | Stars: ").append(stars);
                    if (!language.isBlank()) summary.append(" | ").append(language);
                    if (!desc.isBlank()) summary.append(" | ").append(CrawlerUtils.compactSummary(desc, 200));

                    ResourceClient.CourseResource r = new ResourceClient.CourseResource();
                    r.setTitle(title);
                    r.setPlatform("GitHub");
                    r.setUrl(htmlUrl);
                    r.setSummary(summary.toString());
                    out.add(r);
                    added++;
                }
                log.debug("GitHub query '{}': {} results", q, added);
            } catch (Exception e) {
                log.warn("GitHub crawl failed: query={}, err={}", q, e.getMessage());
            }
        }
        return out;
    }

    public CompletableFuture<Void> crawlTopicAsync(String topic) {
        if (topic == null || topic.isBlank() || !enabled) return CompletableFuture.completedFuture(null);
        Runnable task = () -> {
            if (!onDemandRunning.compareAndSet(false, true)) return;
            try {
                List<ResourceClient.CourseResource> candidates = fetchCandidates(topic, topic, perTopicLimit);
                int saved = 0;
                for (ResourceClient.CourseResource c : candidates) {
                    if (CrawlerUtils.saveIfNew(topic, c, courseResourceMapper, searchRepository, embeddingModel, qualityFilterEnabled))
                        saved++;
                }
                totalCrawled += saved;
                if (saved > 0) { consecutiveZeroNew = 0; consecutiveFailures = 0; }
                else if (candidates.isEmpty()) { consecutiveZeroNew++; }
                log.info("GitHub crawl '{}': candidates={} saved={}", topic, candidates.size(), saved);
            } catch (Exception e) {
                consecutiveFailures++;
                log.warn("GitHub crawl failed for '{}': {}", topic, e.getMessage());
            } finally {
                onDemandRunning.set(false);
            }
        };
        return CompletableFuture.runAsync(task, aiTaskExecutor);
    }

    public void scheduledCrawl(List<String> topics) {
        if (!enabled) return;
        if (!running.compareAndSet(false, true)) return;
        try {
            if (topics.isEmpty()) { lastRunTime = System.currentTimeMillis(); return; }
            lastRunTopicsCount = topics.size();
            int totalNew = 0;
            int failedTopics = 0;
            for (String topic : topics) {
                try {
                    List<ResourceClient.CourseResource> candidates = fetchCandidates(topic, topic, perTopicLimit);
                    for (ResourceClient.CourseResource c : candidates) {
                        if (CrawlerUtils.saveIfNew(topic, c, courseResourceMapper, searchRepository, embeddingModel, qualityFilterEnabled))
                            totalNew++;
                    }
                } catch (Exception e) { failedTopics++; }
                CrawlerUtils.sleep(1500); // stricter rate limit
            }
            lastRunTime = System.currentTimeMillis();
            lastRunNewCount = totalNew;
            totalCrawled += totalNew;
            if (totalNew > 0) { consecutiveZeroNew = 0; consecutiveFailures = 0; }
            else if (failedTopics == topics.size()) { consecutiveFailures++; }
            else { consecutiveZeroNew++; }
            log.info("GitHub crawler finished: {} new from {} topics", totalNew, topics.size());
        } finally {
            running.set(false);
        }
    }
}
