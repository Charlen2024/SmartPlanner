package com.chao.resource.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.client.ResourceClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chao.resource.entity.CourseResource;
import com.chao.resource.mapper.CourseResourceMapper;
import com.chao.resource.search.CourseResourceDocument;
import com.chao.resource.search.CourseResourceSearchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.chao.common.client.GoalClient;
import com.chao.common.dto.Result;
import jakarta.annotation.PostConstruct;

/**
 * Bilibili 爬虫服务：定时 + 按需爬取，质量过滤，入库去重。
 * 从 ResourceService 拆分出来以避免上帝类。
 */
@Slf4j
@Service
public class BilibiliCrawlerService {

    private final CourseResourceMapper courseResourceMapper;
    private final CourseResourceSearchRepository searchRepository;
    private final GoalClient goalClient;
    private final RestTemplate externalRestTemplate;
    private final Executor aiTaskExecutor;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean crawlerRunning = new AtomicBoolean(false);
    private final AtomicBoolean onDemandCrawlerRunning = new AtomicBoolean(false);
    private volatile boolean crawlerPaused = false;

    // --- crawler statistics ---
    private volatile long lastRunTime;
    private volatile int lastRunTopicsCount;
    private volatile int lastRunNewCount;
    private volatile long totalCrawled;
    private volatile int consecutiveFailures;
    private volatile int consecutiveZeroNew;

    @Value("${smartplanner.crawler.bilibili.enabled:true}")
    private boolean bilibiliCrawlerEnabled;
    @Value("${smartplanner.crawler.bilibili.topics:Java,Spring Boot,Python,Vue}")
    private String bilibiliCrawlerTopics;
    @Value("${smartplanner.crawler.bilibili.interval-ms:21600000}")
    private long bilibiliCrawlerIntervalMs;
    @Value("${smartplanner.crawler.bilibili.per-topic-limit:3}")
    private int bilibiliCrawlerPerTopicLimit;
    @Value("${smartplanner.crawler.bilibili.topic-delay-ms:800}")
    private long bilibiliCrawlerTopicDelayMs;

    @Value("${smartplanner.crawler.quality-filter.enabled:true}")
    private boolean qualityFilterEnabled;

    @Value("${smartplanner.crawler.bilibili.query-suffixes:教程,入门,基础}")
    private String bilibiliQuerySuffixes;

    @Value("${smartplanner.crawler.bilibili.use-web-scrape-fallback:true}")
    private boolean bilibiliUseWebScrapeFallback;

    @Value("${smartplanner.http.proxy-host:}")
    private String httpProxyHost;

    @Value("${smartplanner.http.proxy-port:0}")
    private int httpProxyPort;

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");
    private static final Pattern HEX_HASH = Pattern.compile("^[0-9a-fA-F]{32,}$");
    private static final Pattern HEX_HASH_SUFFIX = Pattern.compile(".*_[0-9a-fA-F]{16,}$");
    private static final Pattern CJK_CHAR = Pattern.compile("[\\u4E00-\\u9FFF]");
    private static final Pattern BILIBILI_BV = Pattern.compile("(?i)/video/(BV[0-9A-Za-z]+)");

    public BilibiliCrawlerService(
            CourseResourceMapper courseResourceMapper,
            CourseResourceSearchRepository searchRepository,
            GoalClient goalClient,
            RestTemplate externalRestTemplate,
            @Qualifier("aiTaskExecutor") Executor aiTaskExecutor,
            ObjectMapper objectMapper) {
        this.courseResourceMapper = courseResourceMapper;
        this.searchRepository = searchRepository;
        this.goalClient = goalClient;
        this.externalRestTemplate = externalRestTemplate;
        this.aiTaskExecutor = aiTaskExecutor;
        this.objectMapper = objectMapper;
    }

    // --- crawler getters for actuator endpoint ---
    public boolean isCrawlerRunning() { return crawlerRunning.get(); }
    public boolean isCrawlerPaused() { return crawlerPaused; }
    public long getLastRunTime() { return lastRunTime; }
    public int getLastRunTopicsCount() { return lastRunTopicsCount; }
    public int getLastRunNewCount() { return lastRunNewCount; }
    public long getTotalCrawled() { return totalCrawled; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public int getConsecutiveZeroNew() { return consecutiveZeroNew; }
    public boolean isBilibiliCrawlerEnabled() { return bilibiliCrawlerEnabled; }
    public long getBilibiliCrawlerIntervalMs() { return bilibiliCrawlerIntervalMs; }
    public long getBilibiliCrawlerTopicDelayMs() { return bilibiliCrawlerTopicDelayMs; }
    public int getBilibiliCrawlerPerTopicLimit() { return bilibiliCrawlerPerTopicLimit; }
    public void pauseCrawler() { this.crawlerPaused = true; }
    public void resumeCrawler() { this.crawlerPaused = false; }

    @PostConstruct
    public void initCrawl() {
        Runnable task = () -> {
            try {
                Thread.sleep(3000);
                scheduledBilibiliCrawl();
            } catch (Exception ignored) {
            }
        };
        if (aiTaskExecutor != null) {
            CompletableFuture.runAsync(task, aiTaskExecutor);
        } else {
            CompletableFuture.runAsync(task);
        }
    }

    // exposed for search fallback in ResourceService
    public List<ResourceClient.CourseResource> fetchBilibiliCandidates(String query, String topic, int limit) {
        List<ResourceClient.CourseResource> out = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        List<String> queries = buildSearchQueries(query);
        log.debug("Fetching Bilibili candidates: queries={}, limit={}", queries, limit);

        for (int qi = 0; qi < queries.size(); qi++) {
            String q = queries.get(qi).trim();
            if (q.isEmpty()) continue;
            if (qi > 0) {
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            }
            String apiUrl = "https://api.bilibili.com/x/web-interface/search/all/v2?keyword=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                    + "&order=pubdate";
            String json = httpGetTextWithUA(apiUrl,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "https://www.bilibili.com/",
                    "https://www.bilibili.com");
            if (json == null || json.isBlank()) {
                log.debug("Bilibili query '{}' returned empty/null response", q);
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(json);
                if (root.path("code").asInt() != 0) {
                    log.debug("Bilibili API code != 0 for '{}': code={}", q, root.path("code").asInt());
                    continue;
                }
                JsonNode result = root.path("data").path("result");
                if (!result.isArray()) continue;
                int added = 0;
                int innerCap = Math.max(limit * 4, 24);
                outer:
                for (JsonNode category : result) {
                    if (out.size() >= innerCap) break;
                    if (!"video".equals(category.path("result_type").asText())) continue;
                    JsonNode items = category.path("data");
                    if (!items.isArray()) continue;
                    for (JsonNode item : items) {
                        if (out.size() >= innerCap) continue outer;
                        String arcurl = item.path("arcurl").asText();
                        String bvid = item.path("bvid").asText();
                        String title = item.path("title").asText().replaceAll("<[^>]+>", "").trim();
                        int play = item.path("play").asInt();
                        String author = item.path("author").asText();
                        String description = item.path("description").asText().replaceAll("<[^>]+>", "").trim();
                        int duration = parseBilibiliDuration(item.path("duration").asText());
                        if (title.isBlank()) continue;
                        String url = !arcurl.isBlank() ? arcurl : (!bvid.isBlank() ? "https://www.bilibili.com/video/" + bvid : "");
                        if (url.isBlank()) continue;
                        String canonical = ResourceService.canonicalUrl(url);
                        if (canonical != null && !seenUrls.add(canonical)) continue;
                        StringBuilder summary = new StringBuilder();
                        if (!author.isBlank()) summary.append("UP主: ").append(author).append(" | ");
                        summary.append("播放: ").append(play);
                        if (duration > 0) summary.append(" | ").append(duration).append("分钟");
                        if (!description.isBlank()) summary.append(" | ").append(description);
                        ResourceClient.CourseResource r = new ResourceClient.CourseResource();
                        r.setTitle(title);
                        r.setPlatform("B站");
                        r.setUrl(url);
                        r.setSummary(summary.toString());
                        out.add(r);
                        added++;
                    }
                }
                log.debug("Bilibili query '{}': {} results", q, added);
            } catch (Exception e) {
                log.warn("Bilibili crawl failed: query={}, err={}", q, e.getMessage());
            }
        }
        log.debug("fetchBilibiliCandidates returning {} total results for query='{}'", out.size(), query);
        return out;
    }

    /**
     * B站网页搜索降级抓取 — 直接抓 search.bilibili.com 的 HTML，解析内嵌 JSON。
     */
    List<ResourceClient.CourseResource> scrapeBilibiliWebSearch(String keyword, int limit) {
        List<ResourceClient.CourseResource> out = new ArrayList<>();
        String url = "https://search.bilibili.com/all?keyword=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&search_type=video";
        String html = httpGetTextWithUA(url,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "https://www.bilibili.com/",
                "https://www.bilibili.com");
        if (html == null || html.isBlank()) {
            log.debug("Bilibili web scrape: empty response for '{}'", keyword);
            return out;
        }
        String marker = "window.__INITIAL_STATE__";
        int idx = html.indexOf(marker);
        if (idx < 0) {
            log.debug("Bilibili web scrape: no __INITIAL_STATE__ for '{}'", keyword);
            return out;
        }
        int jsonStart = html.indexOf("{", idx + marker.length());
        int jsonEnd = html.lastIndexOf("};");
        if (jsonStart < 0 || jsonEnd < 0 || jsonEnd <= jsonStart) {
            log.debug("Bilibili web scrape: failed to locate JSON for '{}'", keyword);
            return out;
        }
        String jsonStr = html.substring(jsonStart, jsonEnd + 1);
        try {
            JsonNode root = objectMapper.readTree(jsonStr);
            JsonNode resultList = root.path("flow").path("result");
            if (!resultList.isArray()) {
                JsonNode videoList = root.path("video").path("result");
                if (videoList.isArray()) resultList = videoList;
            }
            if (!resultList.isArray()) return out;
            Set<String> seenUrls = new HashSet<>();
            for (JsonNode item : resultList) {
                if (out.size() >= limit) break;
                String title = item.path("title").asText("").replaceAll("<[^>]+>", "").trim();
                if (title.isBlank()) continue;
                String bvid = item.path("bvid").asText("");
                String arcurl = item.path("arcurl").asText("");
                String videoUrl = !arcurl.isBlank() ? arcurl : (!bvid.isBlank() ? "https://www.bilibili.com/video/" + bvid : "");
                if (videoUrl.isBlank()) continue;
                String canonical = ResourceService.canonicalUrl(videoUrl);
                if (!seenUrls.add(canonical)) continue;
                int play = item.path("play").asInt();
                String author = item.path("author").asText("");
                String desc = item.path("description").asText("").replaceAll("<[^>]+>", "").trim();
                String duration = item.path("duration").asText("");
                int durSec = parseBilibiliDuration(duration);
                StringBuilder summary = new StringBuilder();
                if (!author.isBlank()) summary.append("UP主: ").append(author).append(" | ");
                summary.append("播放: ").append(play);
                if (durSec > 0) summary.append(" | ").append(durSec).append("分钟");
                if (!desc.isBlank()) summary.append(" | ").append(desc);
                ResourceClient.CourseResource r = new ResourceClient.CourseResource();
                r.setTitle(title);
                r.setPlatform("B站");
                r.setUrl(videoUrl);
                r.setSummary(summary.toString());
                out.add(r);
            }
        } catch (Exception e) {
            log.warn("Bilibili web scrape parse failed for '{}': {}", keyword, e.getMessage());
        }
        log.debug("Bilibili web scrape for '{}': {} results", keyword, out.size());
        return out;
    }

    @Scheduled(initialDelayString = "${smartplanner.crawler.bilibili.initial-delay-ms:120000}", fixedDelayString = "${smartplanner.crawler.bilibili.interval-ms:21600000}")
    public void scheduledBilibiliCrawl() {
        if (!bilibiliCrawlerEnabled || crawlerPaused) return;
        if (!crawlerRunning.compareAndSet(false, true)) return;
        try {
            Set<String> topics = new LinkedHashSet<>();
            // 1. Get topics from existing resource DB
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
            // 2. Add topics from user goals (learning interests)
            try {
                Result<List<String>> topicsResult = goalClient.getDistinctTopics();
                List<String> goalTopics = topicsResult != null ? topicsResult.getData() : List.of();
                if (goalTopics != null) {
                    for (String t : goalTopics) {
                        if (t != null && !t.isBlank()) {
                            topics.add(t.trim());
                        }
                    }
                }
                log.debug("Added {} goal-based topics to crawler", goalTopics != null ? goalTopics.size() : 0);
            } catch (Exception e) {
                log.warn("Failed to get goal topics: {}", e.getMessage());
            }
            // 3. Add configured seed topics
            if (bilibiliCrawlerTopics != null && !bilibiliCrawlerTopics.isBlank()) {
                for (String t : bilibiliCrawlerTopics.split(",")) {
                    String trimmed = t.trim();
                    if (!trimmed.isEmpty()) topics.add(trimmed);
                }
            }
            if (topics.isEmpty()) {
                lastRunTime = System.currentTimeMillis();
                lastRunTopicsCount = 0;
                lastRunNewCount = 0;
                return;
            }

            log.info("Bilibili crawler started: {} topics, limit {} per topic", topics.size(), bilibiliCrawlerPerTopicLimit);
            lastRunTopicsCount = topics.size();
            int totalNew = 0;
            int failedTopics = 0;
            int i = 0;
            for (String topic : topics) {
                try {
                    List<ResourceClient.CourseResource> candidates = fetchBilibiliCandidates(topic, topic, bilibiliCrawlerPerTopicLimit);
                    for (ResourceClient.CourseResource c : candidates) {
                        if (saveIfNew(topic, c)) totalNew++;
                    }
                } catch (Exception e) {
                    failedTopics++;
                    log.warn("Crawl failed for topic {}: {}", topic, e.getMessage());
                }
                if (++i < topics.size() && bilibiliCrawlerTopicDelayMs > 0) {
                    try { Thread.sleep(bilibiliCrawlerTopicDelayMs); } catch (InterruptedException ignored) {}
                }
            }
            lastRunTime = System.currentTimeMillis();
            lastRunNewCount = totalNew;
            totalCrawled += totalNew;
            boolean allTopicsFailed = (topics.size() > 0 && failedTopics == topics.size());
            if (totalNew > 0) {
                consecutiveZeroNew = 0;
                consecutiveFailures = 0;
            } else if (allTopicsFailed || (failedTopics > 0 && totalNew == 0)) {
                consecutiveFailures++;
                consecutiveZeroNew = 0;
            } else {
                consecutiveZeroNew++;
                consecutiveFailures = 0;
            }
            log.info("Bilibili crawler finished: {} new resources saved", totalNew);
        } catch (Exception e) {
            consecutiveFailures++;
            lastRunTime = System.currentTimeMillis();
            lastRunNewCount = 0;
            log.error("Bilibili crawler failed", e);
        } finally {
            crawlerRunning.set(false);
        }
    }

    /**
     * 异步爬取指定主题（目标驱动即时爬取），不等待结果。
     */
    public void crawlTopicAsync(String topic) {
        if (topic == null || topic.isBlank()) return;
        if (!bilibiliCrawlerEnabled) return;
        Runnable task = () -> {
            if (!onDemandCrawlerRunning.compareAndSet(false, true)) {
                log.info("On-demand crawl skipped for '{}': another on-demand crawl is already running", topic);
                return;
            }
            try {
                List<ResourceClient.CourseResource> candidates = fetchBilibiliCandidates(topic, topic, bilibiliCrawlerPerTopicLimit);
                log.info("Crawl '{}': fetched {} candidates, filtering...", topic, candidates.size());
                int saved = 0;
                int dups = 0, titleRejects = 0, qualityRejects = 0;
                for (ResourceClient.CourseResource c : candidates) {
                    Long cnt = courseResourceMapper.selectCount(
                            new LambdaQueryWrapper<CourseResource>().eq(CourseResource::getSourceUrl, c.getUrl()));
                    if (cnt != null && cnt > 0) { dups++; continue; }
                    if (!isValidTitle(c.getTitle(), topic)) { titleRejects++; continue; }
                    if (qualityFilterEnabled && !isContentRelevantToTopic(topic, c.getTitle(), c.getSummary())) { qualityRejects++; continue; }
                    if (saveDirect(topic, c)) saved++;
                }
                totalCrawled += saved;
                log.info("Crawl '{}': candidate={} saved={} dup={} titleRej={} qualityRej={}",
                        topic, candidates.size(), saved, dups, titleRejects, qualityRejects);
                if (saved > 0) {
                    consecutiveZeroNew = 0;
                    consecutiveFailures = 0;
                } else if (candidates.isEmpty()) {
                    consecutiveZeroNew++;
                }
                if (saved < 2 && bilibiliUseWebScrapeFallback) {
                    log.info("Crawl '{}': API results insufficient (saved={}), trying web scrape fallback", topic, saved);
                    try {
                        List<ResourceClient.CourseResource> scraped = scrapeBilibiliWebSearch(topic, bilibiliCrawlerPerTopicLimit);
                        log.info("Crawl '{}': web scrape returned {} candidates", topic, scraped.size());
                        for (ResourceClient.CourseResource c : scraped) {
                            if (saveIfNew(topic, c)) saved++;
                        }
                        if (saved > 0) consecutiveZeroNew = 0;
                    } catch (Exception ex) {
                        log.warn("Crawl '{}': web scrape fallback failed: {}", topic, ex.getMessage());
                    }
                }
            } catch (Exception e) {
                consecutiveFailures++;
                log.warn("Goal-driven crawl failed for '{}': {}", topic, e.getMessage());
            } finally {
                onDemandCrawlerRunning.set(false);
            }
        };
        if (aiTaskExecutor != null) {
            CompletableFuture.runAsync(task, aiTaskExecutor);
        } else {
            CompletableFuture.runAsync(task);
        }
    }

    // exposed for search fallback in ResourceService
    public boolean saveIfNew(String topic, ResourceClient.CourseResource c) {
        if (topic == null || c == null || c.getUrl() == null) return false;
        Long count = courseResourceMapper.selectCount(
                new LambdaQueryWrapper<CourseResource>().eq(CourseResource::getSourceUrl, c.getUrl()));
        if (count != null && count > 0) return false;

        if (!isValidTitle(c.getTitle(), topic)) {
            log.debug("Skipping garbage title for topic '{}': title={}", topic, c.getTitle());
            return false;
        }

        if (qualityFilterEnabled && !isContentRelevantToTopic(topic, c.getTitle(), c.getSummary())) {
            log.debug("Skipping irrelevant resource for topic '{}': title={}", topic, c.getTitle());
            return false;
        }

        CourseResource entity = new CourseResource();
        entity.setTopic(topic);
        entity.setTitle(c.getTitle());
        entity.setSourceUrl(c.getUrl());
        entity.setPlatform(c.getPlatform());
        entity.setContentSummary(c.getSummary());
        entity.setCreatedAt(LocalDateTime.now());
        courseResourceMapper.insert(entity);

        try {
            CourseResourceDocument doc = new CourseResourceDocument();
            doc.setId(entity.getId());
            doc.setTopic(topic);
            doc.setTitle(entity.getTitle());
            doc.setPlatform(entity.getPlatform());
            doc.setSourceUrl(entity.getSourceUrl());
            doc.setContentSummary(entity.getContentSummary());
            doc.setCreatedAtEpochMillis(entity.getCreatedAt() != null ? entity.getCreatedAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli() : System.currentTimeMillis());
            generateAndSetEmbedding(doc);
            searchRepository.save(doc);
        } catch (Exception e) {
            log.warn("Failed to index resource to ES: {}", e.getMessage());
        }
        return true;
    }

    private boolean saveDirect(String topic, ResourceClient.CourseResource c) {
        CourseResource entity = new CourseResource();
        entity.setTopic(topic);
        entity.setTitle(c.getTitle());
        entity.setSourceUrl(c.getUrl());
        entity.setPlatform(c.getPlatform());
        entity.setContentSummary(c.getSummary());
        entity.setCreatedAt(LocalDateTime.now());
        courseResourceMapper.insert(entity);
        try {
            CourseResourceDocument doc = new CourseResourceDocument();
            doc.setId(entity.getId());
            doc.setTopic(topic);
            doc.setTitle(entity.getTitle());
            doc.setPlatform(entity.getPlatform());
            doc.setSourceUrl(entity.getSourceUrl());
            doc.setContentSummary(entity.getContentSummary());
            doc.setCreatedAtEpochMillis(entity.getCreatedAt() != null ? entity.getCreatedAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli() : System.currentTimeMillis());
            generateAndSetEmbedding(doc);
            searchRepository.save(doc);
        } catch (Exception e) {
            log.warn("Failed to index resource to ES: {}", e.getMessage());
        }
        return true;
    }

    // --- quality filter ---

    private boolean isValidTitle(String title, String topic) {
        if (title == null || title.isBlank()) return false;
        if (title.length() < 4) return false;
        if (DIGITS_ONLY.matcher(title).matches()) return false;
        if (title.startsWith("%")) return false;
        if (HEX_HASH.matcher(title).matches()) return false;
        if (HEX_HASH_SUFFIX.matcher(title).matches()) return false;
        if (isCJK(topic)) {
            boolean titleHasCJK = CJK_CHAR.matcher(title).find();
            if (!titleHasCJK && title.length() >= 15 && !title.contains(" ")) {
                return false;
            }
        }
        return true;
    }

    private boolean isContentRelevantToTopic(String topic, String title, String summary) {
        if (topic == null || topic.isBlank()) return true;
        if (title == null || title.isBlank()) return false;
        String normTopic = ResourceService.normalizeText(topic);
        String normTitle = ResourceService.normalizeText(title);
        String normSummary = summary != null ? ResourceService.normalizeText(summary) : "";
        if (normTitle.contains(normTopic) || normTopic.contains(normTitle)) return true;
        if (!normSummary.isBlank() && (normSummary.contains(normTopic) || normTopic.contains(normSummary)))
            return true;
        double titleSim = ResourceService.bigramSimilarity(normTopic, normTitle);
        double summarySim = normSummary.isBlank() ? 0.0 : ResourceService.bigramSimilarity(normTopic, normSummary);
        double combinedSim = Math.max(titleSim, summarySim);
        boolean hasCJK = normTopic.codePoints().anyMatch(cp ->
                Character.isIdeographic(cp) || (cp >= 0x4E00 && cp <= 0x9FFF));
        double threshold = hasCJK ? 0.04 : 0.10;
        if (combinedSim >= threshold) return true;
        if (hasCJK && normTopic.length() <= 4) {
            long matchCount = normTopic.codePoints()
                    .filter(cp -> normTitle.indexOf(cp) >= 0)
                    .count();
            if (matchCount >= normTopic.codePoints().count() * 0.5) return true;
        }
        return false;
    }

    // --- query building ---

    private List<String> buildSearchQueries(String topic) {
        List<String> queries = new ArrayList<>();
        queries.add(topic);
        if (isCJK(topic)) {
            // Word-order expansion for 2-4 char CJK topics
            if (topic.length() >= 2 && topic.length() <= 4 && !topic.contains(" ")) {
                String t = topic.trim();
                for (int i = 1; i < t.length(); i++) {
                    String reordered = t.substring(i) + t.substring(0, i);
                    if (!reordered.equals(t)) queries.add(reordered);
                }
            }
            // Suffix expansion
            if (bilibiliQuerySuffixes != null && !bilibiliQuerySuffixes.isBlank()) {
                for (String suffix : bilibiliQuerySuffixes.split(",")) {
                    String s = suffix.trim();
                    if (!s.isEmpty()) queries.add(topic + s);
                }
            }
        } else {
            // English suffix expansion
            if (bilibiliQuerySuffixes != null && !bilibiliQuerySuffixes.isBlank()) {
                String[] enSuffixes = {"tutorial", "basics", "crash course", "project",
                        "full course", "beginner", "advanced", "interview"};
                for (String s : enSuffixes) {
                    queries.add(topic + " " + s);
                }
            }
        }
        // Time-based queries for fresh content
        queries.add(topic + " 2026");
        queries.add(topic + " 最新");
        return queries;
    }

    // --- utility ---

    private void generateAndSetEmbedding(CourseResourceDocument doc) {
        if (embeddingModel == null) return;
        try {
            String text = buildEmbeddingText(doc.getTopic(), doc.getTitle(), doc.getContentSummary());
            if (text.isBlank()) return;
            float[] vector = embeddingModel.embed(text);
            if (vector != null && vector.length > 0) {
                doc.setEmbedding(vector);
            }
        } catch (Exception e) {
            log.debug("Embedding generation failed for doc id={}: {}", doc.getId(), e.getMessage());
        }
    }

    private static String buildEmbeddingText(String topic, String title, String summary) {
        StringBuilder sb = new StringBuilder();
        if (topic != null && !topic.isBlank()) sb.append(topic).append(" ");
        if (title != null && !title.isBlank()) sb.append(title).append(" ");
        if (summary != null && !summary.isBlank()) sb.append(summary);
        String result = sb.toString().trim();
        if (result.length() > 6000) result = result.substring(0, 6000);
        return result;
    }

    private boolean isCJK(String s) {
        return ResourceTextUtils.isCJK(s);
    }

    private int parseBilibiliDuration(String duration) {
        if (duration == null || duration.isBlank()) return 0;
        try {
            String[] parts = duration.split(":");
            if (parts.length == 2) {
                return Integer.parseInt(parts[0]) + (Integer.parseInt(parts[1]) >= 30 ? 1 : 0);
            } else if (parts.length == 3) {
                return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    private String httpGetTextWithUA(String url, String userAgent, String referer, String origin) {
        int maxRetries = 2;
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", userAgent);
        headers.set("Referer", referer);
        if (origin != null) headers.set("Origin", origin);
        headers.set("Accept", "application/json, text/plain, */*");
        headers.set("Accept-Language", "zh-CN,zh;q=0.9");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String requestUrl = url;
        if (httpProxyHost != null && !httpProxyHost.isBlank() && httpProxyPort > 0
                && (url.contains("api.bilibili.com") || url.contains("search.bilibili.com"))) {
            requestUrl = "http://" + httpProxyHost + ":" + httpProxyPort + "/?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8);
        }

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                ResponseEntity<String> resp = externalRestTemplate.exchange(
                        URI.create(requestUrl), HttpMethod.GET, entity, String.class);
                if (resp.getStatusCode().is2xxSuccessful()) {
                    return resp.getBody();
                }
                if (resp.getStatusCodeValue() == 429 || resp.getStatusCode().is5xxServerError()) {
                    if (attempt < maxRetries) {
                        try { Thread.sleep((attempt + 1) * 1000L); } catch (InterruptedException ignored) {}
                        continue;
                    }
                }
                return null;
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    try { Thread.sleep((attempt + 1) * 500L); } catch (InterruptedException ignored) {}
                } else {
                    log.debug("httpGetTextWithUA failed after {} retries: {}", maxRetries, e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }
}
