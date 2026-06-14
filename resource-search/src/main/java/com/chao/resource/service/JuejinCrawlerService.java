package com.chao.resource.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.client.ResourceClient;
import com.chao.common.dto.SearchResourceItem;
import com.chao.resource.mapper.CourseResourceMapper;
import com.chao.resource.search.CourseResourceSearchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 掘金 (juejin.cn) crawler using their public search API.
 */
@Slf4j
@Service
public class JuejinCrawlerService {

    private final CourseResourceMapper courseResourceMapper;
    private final CourseResourceSearchRepository searchRepository;
    private final RestTemplate externalRestTemplate;
    private final Executor aiTaskExecutor;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean onDemandRunning = new AtomicBoolean(false);

    @Value("${smartplanner.crawler.juejin.enabled:true}")
    private boolean enabled;
    @Value("${smartplanner.crawler.juejin.per-topic-limit:5}")
    private int perTopicLimit;
    @Value("${smartplanner.crawler.juejin.query-suffixes:教程,入门,实战,面试}")
    private String querySuffixes;
    @Value("${smartplanner.crawler.quality-filter.enabled:true}")
    private boolean qualityFilterEnabled;

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    private volatile long lastRunTime;
    private volatile int lastRunTopicsCount;
    private volatile int lastRunNewCount;
    private volatile long totalCrawled;
    private volatile int consecutiveFailures;
    private volatile int consecutiveZeroNew;

    public JuejinCrawlerService(
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

    public List<SearchResourceItem> fetchCandidates(String query, String topic, int limit) {
        List<SearchResourceItem> out = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        List<String> queries = CrawlerUtils.buildSearchQueries(query, querySuffixes);

        for (int qi = 0; qi < queries.size(); qi++) {
            String q = queries.get(qi).trim();
            if (q.isEmpty()) continue;
            if (qi > 0) CrawlerUtils.sleep(600);

            String apiUrl = "https://api.juejin.cn/search_api/v1/search";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.set("Origin", "https://juejin.cn");
            headers.set("Referer", "https://juejin.cn/");

            String body;
            try {
                body = objectMapper.writeValueAsString(
                        java.util.Map.of(
                                "query", q,
                                "limit", Math.min(limit, 20),
                                "sort_type", 0,
                                "cursor", "0",
                                "id_type", 2
                        ));
            } catch (Exception e) { continue; }

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            String json = null;
            for (int attempt = 0; attempt <= 2; attempt++) {
                try {
                    ResponseEntity<String> resp = externalRestTemplate.exchange(
                            URI.create(apiUrl), HttpMethod.POST, entity, String.class);
                    if (resp.getStatusCode().is2xxSuccessful()) { json = resp.getBody(); break; }
                    if (attempt < 2) CrawlerUtils.sleep((attempt + 1) * 1000L);
                } catch (Exception e) {
                    if (attempt < 2) CrawlerUtils.sleep(500);
                    else log.debug("Juejin HTTP failed: {}", e.getMessage());
                }
            }
            if (json == null || json.isBlank()) continue;

            try {
                JsonNode root = objectMapper.readTree(json);
                JsonNode items = root.path("data");
                if (!items.isArray()) continue;
                int added = 0;
                for (JsonNode item : items) {
                    if (out.size() >= limit * 3) break;
                    JsonNode info = item.path("article_info");
                    String title = info.path("title").asText("").trim();
                    String brief = info.path("brief_content").asText("").trim();
                    String articleId = info.path("article_id").asText("");
                    JsonNode authorInfo = item.path("author_user_info");
                    String author = authorInfo.path("user_name").asText("");

                    if (title.isBlank()) continue;
                    String url = !articleId.isBlank() ? "https://juejin.cn/post/" + articleId : "";
                    if (url.isBlank()) continue;
                    String canonical = ResourceService.canonicalUrl(url);
                    if (canonical != null && !seenUrls.add(canonical)) continue;

                    StringBuilder summary = new StringBuilder();
                    if (!author.isBlank()) summary.append("作者: ").append(author).append(" | ");
                    summary.append(CrawlerUtils.compactSummary(brief, 200));

                    SearchResourceItem r = new SearchResourceItem();
                    r.setTitle(title);
                    r.setPlatform("掘金");
                    r.setUrl(url);
                    r.setSummary(summary.toString());
                    out.add(r);
                    added++;
                }
                log.debug("Juejin query '{}': {} results", q, added);
            } catch (Exception e) {
                log.warn("Juejin parse failed: query={}, err={}", q, e.getMessage());
            }
        }
        return out;
    }

    public CompletableFuture<Void> crawlTopicAsync(String topic) {
        if (topic == null || topic.isBlank() || !enabled) return CompletableFuture.completedFuture(null);
        Runnable task = () -> {
            if (!onDemandRunning.compareAndSet(false, true)) return;
            try {
                List<SearchResourceItem> candidates = fetchCandidates(topic, topic, perTopicLimit);
                int saved = 0;
                for (SearchResourceItem c : candidates) {
                    if (CrawlerUtils.saveIfNew(topic, c, courseResourceMapper, searchRepository, embeddingModel, qualityFilterEnabled))
                        saved++;
                }
                totalCrawled += saved;
                if (saved > 0) { consecutiveZeroNew = 0; consecutiveFailures = 0; }
                log.info("Juejin crawl '{}': candidates={} saved={}", topic, candidates.size(), saved);
            } catch (Exception e) {
                consecutiveFailures++;
                log.warn("Juejin crawl failed for '{}': {}", topic, e.getMessage());
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
            for (int i = 0; i < topics.size(); i++) {
                String topic = topics.get(i);
                try {
                    List<SearchResourceItem> candidates = fetchCandidates(topic, topic, perTopicLimit);
                    for (SearchResourceItem c : candidates) {
                        if (CrawlerUtils.saveIfNew(topic, c, courseResourceMapper, searchRepository, embeddingModel, qualityFilterEnabled))
                            totalNew++;
                    }
                } catch (Exception e) { failedTopics++; }
                if (i < topics.size() - 1) CrawlerUtils.sleep(1000);
            }
            lastRunTime = System.currentTimeMillis();
            lastRunNewCount = totalNew;
            totalCrawled += totalNew;
            if (totalNew > 0) { consecutiveZeroNew = 0; consecutiveFailures = 0; }
            else if (failedTopics == topics.size()) { consecutiveFailures++; }
            else { consecutiveZeroNew++; }
            log.info("Juejin crawler finished: {} new from {} topics", totalNew, topics.size());
        } finally {
            running.set(false);
        }
    }
}
