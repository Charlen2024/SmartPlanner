package com.chao.resource.service;

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
 * 慕课网 (imooc.com) web-scraping crawler.
 * Parses search result HTML to extract course cards.
 */
@Slf4j
@Service
public class ImoocCrawlerService {

    private final CourseResourceMapper courseResourceMapper;
    private final CourseResourceSearchRepository searchRepository;
    private final RestTemplate externalRestTemplate;
    private final Executor aiTaskExecutor;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean onDemandRunning = new AtomicBoolean(false);

    @Value("${smartplanner.crawler.imooc.enabled:true}")
    private boolean enabled;
    @Value("${smartplanner.crawler.imooc.per-topic-limit:5}")
    private int perTopicLimit;
    @Value("${smartplanner.crawler.imooc.query-suffixes:入门,实战,项目}")
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

    // Imooc search result item patterns
    private static final Pattern CARD_PATTERN = Pattern.compile(
            "<a[^>]*href=\"(/course/\\d+)\"[^>]*>\\s*(?:<[^>]*>)*\\s*([^<]+)\\s*(?:</[^>]*>)*\\s*</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TITLE_LINK_PATTERN = Pattern.compile(
            "href=\"(/course/\\d+)\"[^>]*>[^<]*<span[^>]*>([^<]+)</span>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DESC_PATTERN = Pattern.compile(
            "<p[^>]*class=\"[^\"]*description[^\"]*\"[^>]*>([^<]+)</p>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LEVEL_PATTERN = Pattern.compile(
            "class=\"[^\"]*level[^\"]*\"[^>]*>([^<]+)<",
            Pattern.CASE_INSENSITIVE);

    public ImoocCrawlerService(
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

    public List<SearchResourceItem> fetchCandidates(String query, String topic, int limit) {
        List<SearchResourceItem> out = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        List<String> queries = CrawlerUtils.buildSearchQueries(query, querySuffixes);

        for (int qi = 0; qi < queries.size() && out.size() < limit * 3; qi++) {
            String q = queries.get(qi).trim();
            if (q.isEmpty()) continue;
            if (qi > 0) CrawlerUtils.sleep(600);

            String url = "https://www.imooc.com/search/?words=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            headers.set("Accept-Language", "zh-CN,zh;q=0.9");

            String html = CrawlerUtils.httpGet(externalRestTemplate, url, headers, 1);
            if (html == null || html.isBlank()) continue;

            try {
                List<ImoocCard> cards = parseCards(html);
                for (ImoocCard card : cards) {
                    if (out.size() >= limit * 3) break;
                    if (card.title.isBlank() || card.url.isBlank()) continue;
                    String canonical = ResourceService.canonicalUrl(card.url);
                    if (canonical != null && !seenUrls.add(canonical)) continue;

                    StringBuilder summary = new StringBuilder();
                    summary.append("慕课网课程");
                    if (!card.level.isBlank()) summary.append(" | 难度: ").append(card.level);
                    if (!card.desc.isBlank()) summary.append(" | ").append(CrawlerUtils.compactSummary(card.desc, 200));

                    SearchResourceItem r = new SearchResourceItem();
                    r.setTitle(card.title);
                    r.setPlatform("慕课网");
                    r.setUrl(card.url);
                    r.setSummary(summary.toString());
                    out.add(r);
                }
                log.debug("Imooc query '{}': {} results", q, cards.size());
            } catch (Exception e) {
                log.warn("Imooc parse failed: query={}, err={}", q, e.getMessage());
            }
        }
        return out;
    }

    private List<ImoocCard> parseCards(String html) {
        List<ImoocCard> cards = new ArrayList<>();
        // Strategy: find <a> tags containing /course/ and extract surrounding info
        Matcher m = TITLE_LINK_PATTERN.matcher(html);
        while (m.find()) {
            ImoocCard card = new ImoocCard();
            card.url = "https://www.imooc.com" + m.group(1);
            card.title = m.group(2).replaceAll("<[^>]+>", "").trim();
            cards.add(card);
        }
        // Fallback: broader pattern
        if (cards.isEmpty()) {
            Matcher m2 = CARD_PATTERN.matcher(html);
            while (m2.find()) {
                ImoocCard card = new ImoocCard();
                card.url = "https://www.imooc.com" + m2.group(1);
                card.title = m2.group(2).replaceAll("<[^>]+>", "").trim();
                cards.add(card);
            }
        }
        // Try to extract descriptions from JSON-LD or meta tags
        for (ImoocCard card : cards) {
            // Look for nearby description
            int idx = html.indexOf(card.url);
            if (idx >= 0) {
                String nearby = html.substring(Math.max(0, idx - 500), Math.min(html.length(), idx + 500));
                Matcher dm = DESC_PATTERN.matcher(nearby);
                if (dm.find()) card.desc = dm.group(1).trim();
                Matcher lm = LEVEL_PATTERN.matcher(nearby);
                if (lm.find()) card.level = lm.group(1).trim();
            }
        }
        return cards;
    }

    private static class ImoocCard {
        String title = "";
        String url = "";
        String desc = "";
        String level = "";
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
                log.info("Imooc crawl '{}': candidates={} saved={}", topic, candidates.size(), saved);
            } catch (Exception e) {
                consecutiveFailures++;
                log.warn("Imooc crawl failed for '{}': {}", topic, e.getMessage());
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
                    List<SearchResourceItem> candidates = fetchCandidates(topic, topic, perTopicLimit);
                    for (SearchResourceItem c : candidates) {
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
            log.info("Imooc crawler finished: {} new from {} topics", totalNew, topics.size());
        } finally {
            running.set(false);
        }
    }
}
