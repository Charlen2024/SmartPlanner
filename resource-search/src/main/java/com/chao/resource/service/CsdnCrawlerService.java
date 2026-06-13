package com.chao.resource.service;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSDN (csdn.net) web-scraping crawler.
 * Parses search result HTML to extract blog/article items.
 */
@Slf4j
@Service
public class CsdnCrawlerService {

    private final CourseResourceMapper courseResourceMapper;
    private final CourseResourceSearchRepository searchRepository;
    private final RestTemplate externalRestTemplate;
    private final Executor aiTaskExecutor;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean onDemandRunning = new AtomicBoolean(false);

    @Value("${smartplanner.crawler.csdn.enabled:true}")
    private boolean enabled;
    @Value("${smartplanner.crawler.csdn.per-topic-limit:5}")
    private int perTopicLimit;
    @Value("${smartplanner.crawler.csdn.query-suffixes:教程,入门,实战,面试}")
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

    // CSDN article link: https://blog.csdn.net/{user}/article/details/{id}
    private static final Pattern ARTICLE_LINK_PATTERN = Pattern.compile(
            "href=\"(https?://blog\\.csdn\\.net/[^\"]+/article/details/\\d+)\"[^>]*>\\s*(?:<[^>]*>)*\\s*(.+?)\\s*(?:</[^>]*>)*\\s*</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern EMPHASIS_TITLE = Pattern.compile(
            "<em[^>]*>([^<]+)</em>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DESC_ITEM = Pattern.compile(
            "<span[^>]*class=\"[^\"]*content[^\"]*\"[^>]*>(.+?)</span>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern AUTHOR_PATTERN = Pattern.compile(
            "class=\"[^\"]*nickname[^\"]*\"[^>]*>([^<]+)<",
            Pattern.CASE_INSENSITIVE);

    public CsdnCrawlerService(
            CourseResourceMapper courseResourceMapper,
            CourseResourceSearchRepository searchRepository,
            RestTemplate externalRestTemplate,
            @Qualifier("aiTaskExecutor") Executor aiTaskExecutor,
            ObjectMapper objectMapper) {
        this.courseResourceMapper = courseResourceMapper;
        this.searchRepository = searchRepository;
        this.externalRestTemplate = externalRestTemplate;
        this.aiTaskExecutor = aiTaskExecutor;
    }

    public boolean isEnabled() { return enabled; }
    public boolean isRunning() { return running.get(); }
    public long getLastRunTime() { return lastRunTime; }
    public int getLastRunTopicsCount() { return lastRunTopicsCount; }
    public int getLastRunNewCount() { return lastRunNewCount; }
    public long getTotalCrawled() { return totalCrawled; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public int getConsecutiveZeroNew() { return consecutiveZeroNew; }

    public List<ResourceClient.CourseResource> fetchCandidates(String query, String topic, int limit) {
        List<ResourceClient.CourseResource> out = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        List<String> queries = CrawlerUtils.buildSearchQueries(query, querySuffixes);

        for (int qi = 0; qi < queries.size() && out.size() < limit * 3; qi++) {
            String q = queries.get(qi).trim();
            if (q.isEmpty()) continue;
            if (qi > 0) CrawlerUtils.sleep(600);

            String url = "https://so.csdn.net/so/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&t=blog";
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            headers.set("Accept-Language", "zh-CN,zh;q=0.9");

            String html = CrawlerUtils.httpGet(externalRestTemplate, url, headers, 1);
            if (html == null || html.isBlank()) continue;

            try {
                Matcher m = ARTICLE_LINK_PATTERN.matcher(html);
                while (m.find() && out.size() < limit * 3) {
                    String articleUrl = m.group(1);
                    String rawTitle = m.group(2);

                    // Clean title: remove <em> tags, strip HTML
                    String title = rawTitle.replaceAll("<[^>]+>", "").trim();
                    if (title.isBlank()) continue;

                    String canonical = ResourceService.canonicalUrl(articleUrl);
                    if (canonical != null && !seenUrls.add(canonical)) continue;

                    StringBuilder summary = new StringBuilder();
                    summary.append("CSDN博客");
                    // Try to find author nearby
                    int idx = html.indexOf(articleUrl);
                    if (idx >= 0) {
                        String nearby = html.substring(Math.max(0, idx - 1000), Math.min(html.length(), idx + 2000));
                        Matcher dm = DESC_ITEM.matcher(nearby);
                        if (dm.find()) summary.append(" | ").append(CrawlerUtils.compactSummary(dm.group(1).replaceAll("<[^>]+>", "").trim(), 200));
                        Matcher am = AUTHOR_PATTERN.matcher(nearby);
                        if (am.find()) summary.append(" | 作者: ").append(am.group(1).trim());
                    }

                    ResourceClient.CourseResource r = new ResourceClient.CourseResource();
                    r.setTitle(title);
                    r.setPlatform("CSDN");
                    r.setUrl(articleUrl);
                    r.setSummary(summary.toString());
                    out.add(r);
                }
                log.debug("CSDN query '{}': {} results", q, out.size());
            } catch (Exception e) {
                log.warn("CSDN parse failed: query={}, err={}", q, e.getMessage());
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
                log.info("CSDN crawl '{}': candidates={} saved={}", topic, candidates.size(), saved);
            } catch (Exception e) {
                consecutiveFailures++;
                log.warn("CSDN crawl failed for '{}': {}", topic, e.getMessage());
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
                CrawlerUtils.sleep(1500);
            }
            lastRunTime = System.currentTimeMillis();
            lastRunNewCount = totalNew;
            totalCrawled += totalNew;
            if (totalNew > 0) { consecutiveZeroNew = 0; consecutiveFailures = 0; }
            else if (failedTopics == topics.size()) { consecutiveFailures++; }
            else { consecutiveZeroNew++; }
            log.info("CSDN crawler finished: {} new from {} topics", totalNew, topics.size());
        } finally {
            running.set(false);
        }
    }
}
