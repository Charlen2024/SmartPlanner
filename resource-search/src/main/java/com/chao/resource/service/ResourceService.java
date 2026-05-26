package com.chao.resource.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.client.ResourceClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chao.resource.entity.CourseResource;
import com.chao.resource.mapper.CourseResourceMapper;
import com.chao.resource.search.CourseResourceDocument;
import com.chao.resource.search.CourseResourceSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import com.chao.common.client.GoalClient;
import com.chao.common.dto.Result;
import com.chao.common.dto.GoalDto;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceService {

    private final CourseResourceMapper courseResourceMapper;
    private final CourseResourceSearchRepository searchRepository;
    private final ElasticsearchOperations elasticsearchOperations;
    private final OpenAiCompatClient openAiCompatClient;
    private final ObjectMapper objectMapper;
    private final GoalClient goalClient;
    private final AtomicBoolean crawlerRunning = new AtomicBoolean(false);

    @Value("${smartplanner.crawler.bilibili.enabled:true}")
    private boolean bilibiliCrawlerEnabled;
    @Value("${smartplanner.crawler.bilibili.topics:Java,Spring Boot,Python,Vue}")
    private String bilibiliCrawlerTopics;
    @Value("${smartplanner.crawler.bilibili.interval-ms:21600000}")
    private long bilibiliCrawlerIntervalMs;
    @Value("${smartplanner.crawler.bilibili.per-topic-limit:3}")
    private int bilibiliCrawlerPerTopicLimit;

    @Value("${smartplanner.ai.rag-timeout-seconds:45}")
    private int ragTimeoutSeconds;
    @Value("${smartplanner.ai.advice-timeout-seconds:90}")
    private int adviceTimeoutSeconds;

    private static final Pattern TITLE_NOISE = Pattern.compile("(第\\s*\\d+\\s*集|ep\\s*\\d+|p\\s*\\d+|\\d+\\s*分钟|\\d+\\s*秒|时长[:：]?\\s*\\d+\\s*(秒|分钟)|bv\\w+|【购课[^】]*】|chapter\\s*\\d+|unit\\s*\\d+|\\d+(?:\\.\\d+)?--[^\\s]+|\\([^)]*\\)|（[^）]*）)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{IsHan}\\p{IsAlphabetic}\\p{IsDigit}]+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern TITLE_SPLIT = Pattern.compile("\\s*[-－—]\\s*");
    private static final Pattern BILIBILI_BV = Pattern.compile("(?i)/video/(BV[0-9A-Za-z]+)");
    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");

    @jakarta.annotation.PostConstruct
    public void initCrawl() {
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                scheduledBilibiliCrawl();
            } catch (Exception ignored) {
            }
        }, "crawler-startup").start();
    }

    public CourseResource createResource(String topic, String title, String platform, String url, String summary) {
        CourseResource cr = new CourseResource();
        cr.setTopic(topic);
        cr.setTitle(title);
        String canonical = canonicalUrl(url);
        cr.setPlatform(normalizePlatform(platform, canonical));
        cr.setSourceUrl(canonical);
        cr.setContentSummary(summary);
        cr.setCreatedAt(LocalDateTime.now());
        courseResourceMapper.insert(cr);
        upsertToEs(cr);
        return cr;
    }

    public CourseResource getResource(Long id) {
        return courseResourceMapper.selectById(id);
    }

    public List<CourseResource> listResources(String topic) {
        LambdaQueryWrapper<CourseResource> qw = new LambdaQueryWrapper<CourseResource>().orderByDesc(CourseResource::getCreatedAt);
        if (topic != null && !topic.isBlank()) {
            qw.eq(CourseResource::getTopic, topic);
        }
        return courseResourceMapper.selectList(qw);
    }

    public void deleteResource(Long id) {
        courseResourceMapper.deleteById(id);
        try {
            searchRepository.deleteById(id);
        } catch (Exception ignored) {
        }
    }

    /**
     * 语义资源检索
     * 1. 将关键词转化为向量
     * 2. 在 Elasticsearch 中执行向量相似度搜索
     * 3. 如果 ES 未配置或搜索失败，降级为数据库模糊搜索
     */
    public List<ResourceClient.CourseResource> searchResources(String topic) {
        log.info("开始检索资源，关键词: {}", topic);

        try {
            String q = topic != null ? topic.trim() : "";
            if (q.isBlank()) {
                return defaultResources(topic);
            }

            List<CourseResourceDocument> es = searchFromEs(q, 20);
            if (es != null && !es.isEmpty()) {
                List<ResourceClient.CourseResource> out = es.stream()
                        .map(d -> toClientDto(q, d))
                        .filter(Objects::nonNull)
                        .toList();
                out = dedupeResources(q, out, 20);
                if (!out.isEmpty()) return out;
            }

            List<ResourceClient.CourseResource> ai = recommendByAi(q);
            if (ai != null && !ai.isEmpty()) {
                persistAiResources(q, ai);
                return dedupeResources(q, ai, 20);
            }

            List<CourseResource> dbResources = courseResourceMapper.selectList(new LambdaQueryWrapper<CourseResource>()
                    .like(CourseResource::getTopic, q)
                    .or()
                    .like(CourseResource::getTitle, q));

            List<ResourceClient.CourseResource> out = dbResources.stream()
                    .map(r -> {
                        ResourceClient.CourseResource dto = new ResourceClient.CourseResource();
                        dto.setTitle(r.getTitle());
                        dto.setPlatform(r.getPlatform());
                        dto.setUrl(canonicalUrl(r.getSourceUrl()));
                        dto.setSummary(r.getContentSummary());
                        return dto;
                    })
                    .collect(Collectors.toList());

            out = dedupeResources(q, out, 20);
                        // Try Bilibili real-time before Google fallback
            if (out.isEmpty() && !q.isBlank()) {
                try {
                    List<ResourceClient.CourseResource> bilibiliResults = fetchBilibiliCandidates(q, q, 6);
                    if (bilibiliResults != null && !bilibiliResults.isEmpty()) {
                        for (ResourceClient.CourseResource r : bilibiliResults) {
                            if (r != null && r.getUrl() != null) {
                                out.add(r);
                            }
                        }
                        // Persist crawled results
                        for (ResourceClient.CourseResource r : bilibiliResults) {
                            saveIfNew(q, r);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if (out.isEmpty()) return defaultResources(topic);
            return out;
        } catch (Exception e) {
            log.error("检索资源失败", e);
            return defaultResources(topic);
        }
    }

    public ResourceClient.ResourceAdviceResponse searchResourcesWithAdvice(String topic) {
        String q = topic != null ? topic.trim() : "";
        ResourceClient.ResourceAdviceResponse resp = new ResourceClient.ResourceAdviceResponse();
        resp.setTopic(q);

        if (q.isBlank()) {
            resp.setAdvice("请先输入一个明确的学习主题，例如：分布式系统 / 操作系统 / 机器学习。");
            resp.setResources(defaultResources(topic));
            return resp;
        }

        List<CourseResourceDocument> es = searchFromEs(q, 30);
        if (es == null) es = List.of();

        List<String> relatedTopics = findRelatedTopics(q, 8);
        List<CourseResource> db = searchFromDb(q, relatedTopics, 40);

        ResourceClient.ResourceAdviceResponse cached = tryBuildAdviceFromDb(q, relatedTopics, db);
        if (cached != null) {
            return cached;
        }

        ResourceClient.ResourceAdviceResponse fast = tryBuildAdviceFromSearchCandidates(q, relatedTopics, es, db);
        if (fast != null) {
            return fast;
        }

        ResourceClient.ResourceAdviceResponse rag = adviseFromCandidates(q, relatedTopics, es, db);
        if (rag != null && rag.getResources() != null && !rag.getResources().isEmpty()) {
            upsertFromModel(q, rag.getResources());
            return rag;
        }

        resp.setAdvice("建议生成超时或暂不可用，先给你返回当前可检索到的资源列表。你也可以补充：当前水平/目标/截止时间，我再帮你细化路径。");
        resp.setResources(buildFastResources(q, es, db, 10));
        return resp;
    }

    private ResourceClient.ResourceAdviceResponse tryBuildAdviceFromSearchCandidates(
            String topic,
            List<String> relatedTopics,
            List<CourseResourceDocument> esCandidates,
            List<CourseResource> dbCandidates) {
        if (topic == null || topic.isBlank()) return null;

        List<ResourceClient.CourseResource> resources = buildFastResources(topic, esCandidates, dbCandidates, 12);
        if (resources == null || resources.size() < 6) return null;

        ResourceClient.ResourceAdviceResponse resp = new ResourceClient.ResourceAdviceResponse();
        resp.setTopic(topic);
        String advice = buildLlmAdvice(topic, relatedTopics, esCandidates, dbCandidates);
        resp.setAdvice(advice != null && !advice.isBlank()
                ? advice
                : "建议生成超时或暂不可用，先给你返回当前可检索到的资源列表。你也可以补充：当前水平/目标/截止时间，我再帮你细化路径。");
        resp.setResources(resources.subList(0, 6));
        return resp;
    }

    private ResourceClient.ResourceAdviceResponse tryBuildAdviceFromDb(String topic, List<String> relatedTopics, List<CourseResource> dbCandidates) {
        if (topic == null || topic.isBlank()) return null;
        if (dbCandidates == null || dbCandidates.isEmpty()) return null;

        List<ResourceClient.CourseResource> resources = dbCandidates.stream()
                .filter(r -> r.getSourceUrl() != null && r.getSourceUrl().startsWith("https://"))
                .filter(r -> r.getTitle() != null && !r.getTitle().isBlank())
                .filter(r -> r.getContentSummary() != null && !r.getContentSummary().isBlank())
                .limit(8)
                .map(r -> {
                    ResourceClient.CourseResource dto = new ResourceClient.CourseResource();
                    dto.setTitle(r.getTitle());
                    dto.setPlatform(r.getPlatform() != null && !r.getPlatform().isBlank() ? r.getPlatform() : "推荐");
                    dto.setUrl(canonicalUrl(r.getSourceUrl()));
                    dto.setSummary(r.getContentSummary());
                    return dto;
                })
                .toList();

        resources = dedupeResources(topic, resources, 8);
        if (resources.size() < 6) return null;

        ResourceClient.ResourceAdviceResponse resp = new ResourceClient.ResourceAdviceResponse();
        resp.setTopic(topic);
        String advice = buildLlmAdvice(topic, relatedTopics, null, dbCandidates);
        resp.setAdvice(advice != null && !advice.isBlank()
                ? advice
                : "建议生成超时或暂不可用，先给你返回当前可检索到的资源列表。你也可以补充：当前水平/目标/截止时间，我再帮你细化路径。");
        resp.setResources(resources.subList(0, 6));
        return resp;
    }

    private String buildHeuristicAdvice(String topic, List<String> relatedTopics, List<CourseResource> dbCandidates) {
        String topPlatform = null;
        try {
            Map<String, Long> byPlatform = (dbCandidates != null ? dbCandidates : List.<CourseResource>of()).stream()
                    .map(CourseResource::getPlatform)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            topPlatform = byPlatform.entrySet().stream()
                    .max(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey)
                    .orElse(null);
        } catch (Exception ignored) {
        }

        String topicsHint = relatedTopics != null && !relatedTopics.isEmpty()
                ? "可从这些相关方向扩展：" + String.join(" / ", relatedTopics) + "。"
                : "";
        String platformHint = topPlatform != null && !topPlatform.isBlank()
                ? "当前资源主要集中在：" + topPlatform + "。"
                : "";

        boolean cs = isCsTopic(topic);
        String main = cs
                ? "基于现有资源库，建议你围绕「" + topic + "」按“概念 → 经典问题 → 小项目落地 → 进阶专题”推进：先掌握核心概念与常见权衡，再用一个小项目把关键链路跑通，最后针对薄弱点补齐专题。"
                : "基于现有资源库，建议你围绕「" + topic + "」按“概念 → 例题 → 习题巩固 → 总结提升”推进：先把定义与基本方法学清楚，再通过典型例题掌握套路，最后用成体系的练习巩固，并定期复盘错题。";

        return main
                + (platformHint.isBlank() ? "" : "\n" + platformHint)
                + (topicsHint.isBlank() ? "" : "\n" + topicsHint);
    }

    private String buildLlmAdvice(
            String topic,
            List<String> relatedTopics,
            List<CourseResourceDocument> esCandidates,
            List<CourseResource> dbCandidates) {
        boolean hasEs = esCandidates != null && !esCandidates.isEmpty();
        boolean hasDb = dbCandidates != null && !dbCandidates.isEmpty();
        if (!hasEs && !hasDb) return null;

        String ctx = buildCandidateContextJson(topic, esCandidates, dbCandidates, 10);
        if (ctx == null) ctx = "[]";

        String topicJson;
        try {
            topicJson = objectMapper.writeValueAsString(relatedTopics != null ? relatedTopics : List.of());
        } catch (Exception e) {
            topicJson = "[]";
        }

        String dbStatsJson;
        try {
            List<CourseResource> safe = dbCandidates != null ? dbCandidates : List.of();
            Map<String, Long> byTopic = safe.stream()
                    .map(CourseResource::getTopic)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            java.util.HashMap<String, Object> m = new java.util.HashMap<>();
            m.put("byTopic", byTopic);
            dbStatsJson = objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            dbStatsJson = "{}";
        }

        java.util.LinkedHashSet<String> platforms = new java.util.LinkedHashSet<>();
        String platformsJson;
        try {
            if (dbCandidates != null) {
                for (CourseResource r : dbCandidates) {
                    if (r == null) continue;
                    String u = canonicalUrl(r.getSourceUrl());
                    String p = normalizePlatform(r.getPlatform(), u);
                    if (p != null && !p.isBlank() && !p.equals("推荐")) platforms.add(p);
                    if (platforms.size() >= 8) break;
                }
            }
            if (esCandidates != null && platforms.size() < 8) {
                for (CourseResourceDocument d : esCandidates) {
                    if (d == null) continue;
                    String u = canonicalUrl(d.getSourceUrl());
                    String p = normalizePlatform(d.getPlatform(), u);
                    if (p != null && !p.isBlank() && !p.equals("推荐")) platforms.add(p);
                    if (platforms.size() >= 8) break;
                }
            }
            platformsJson = objectMapper.writeValueAsString(new ArrayList<>(platforms));
        } catch (Exception e) {
            platformsJson = "[]";
        }

        String prompt = ""
                + "你是学习规划助手。\n"
                + "主题：" + topic + "\n\n"
                + "数据库中与主题相关的已有分类（JSON数组）：\n"
                + topicJson + "\n\n"
                + "数据库候选资源统计（JSON对象）：\n"
                + dbStatsJson + "\n\n"
                + "候选平台（去噪后的列表，JSON数组）：\n"
                + platformsJson + "\n\n"
                + "系统检索到的候选资源（信息可能不全，JSON数组，字段可能为空）：\n"
                + ctx + "\n\n"
                + "任务：只输出 200~400 字的学习路径建议（入门->进阶）、每一步怎么学、常见坑与注意点。\n"
                + "硬性要求：\n"
                + "- 建议必须结合候选资源的平台与内容类型（例如视频/文章/官方文档/项目）。\n"
                + "- 不要出现以下固定模板句式或其变体：\"基于现有资源库\"、\"概念 → 例题 → 习题巩固 → 总结提升\"。\n"
                + "只输出严格 JSON 对象，不要 Markdown，不要额外文字。\n"
                + "{\"advice\":\"...\"}\n";

        String advice = callAdviceJson(prompt, topic, adviceTimeoutSeconds);
        if (advice == null || advice.isBlank()) return null;
        if (isTemplateAdvice(advice) || advice.length() < 60 || (!platforms.isEmpty() && platforms.stream().noneMatch(advice::contains))) {
            String retryPrompt = prompt
                    + "\n校验失败：你输出了模板化内容或没有结合候选平台。请严格遵守硬性要求，重新输出。\n";
            String retryAdvice = callAdviceJson(retryPrompt, topic, adviceTimeoutSeconds);
            if (retryAdvice != null && !retryAdvice.isBlank()
                    && !isTemplateAdvice(retryAdvice)
                    && retryAdvice.length() >= 60
                    && (platforms.isEmpty() || platforms.stream().anyMatch(retryAdvice::contains))) {
                return retryAdvice.trim();
            }
            return null;
        }
        return advice.trim();
    }

    private String callAdviceJson(String prompt, String topic, int timeoutSeconds) {
        String text;
        try {
            text = CompletableFuture
                    .supplyAsync(() -> openAiCompatClient.complete(prompt))
                    .orTimeout(Math.max(5, timeoutSeconds), TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            Throwable t = unwrapThrowable(e);
            log.warn("LLM advice 生成失败，topic={}, err={}", topic, t != null ? (t.getClass().getName() + ": " + t.getMessage()) : String.valueOf(e));
            return null;
        }

        String json = extractJsonObject(text);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.isObject()) return null;
        String advice = root.path("advice").asText("");
        if (advice == null || advice.isBlank()) return null;
        return advice.trim();
    }

    private boolean isTemplateAdvice(String advice) {
        if (advice == null) return true;
        String s = advice.trim();
        if (s.isBlank()) return true;
        String compact = s.replace(" ", "");
        if (compact.contains("基于现有资源库")) return true;
        if (compact.contains("概念→例题→习题巩固→总结提升")) return true;
        if (compact.contains("概念→经典问题→小项目落地→进阶专题")) return true;
        if (compact.contains("概念") && compact.contains("例题") && compact.contains("习题") && compact.contains("总结")) return true;
        return false;
    }

    private List<ResourceClient.CourseResource> buildFastResources(String topic, List<CourseResourceDocument> es, List<CourseResource> db, int limit) {
        List<ResourceClient.CourseResource> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> seenTitleKey = new HashSet<>();
        List<String> seenBases = new ArrayList<>();

        if (db != null) {
            for (CourseResource r : db) {
                if (out.size() >= limit) break;
                String url = canonicalUrl(r.getSourceUrl());
                if (url == null || !url.startsWith("https://")) continue;
                if (!seen.add(url)) continue;
                String platform = normalizePlatform(r.getPlatform(), url);
                if (!isRelevantCandidate(topic, r.getTopic(), r.getTitle(), r.getContentSummary(), platform, url)) continue;
                String key = normalizeTitleKey(r.getTitle(), topic, platform);
                if (!seenTitleKey.add(key)) continue;
                if (isNearDuplicateBase(normalizeTitleBase(r.getTitle(), topic), seenBases)) continue;
                seenBases.add(normalizeTitleBase(r.getTitle(), topic));
                ResourceClient.CourseResource dto = new ResourceClient.CourseResource();
                dto.setTitle(r.getTitle() != null && !r.getTitle().isBlank() ? r.getTitle() : topic + " 学习资源");
                dto.setPlatform(platform);
                dto.setUrl(url);
                dto.setSummary(r.getContentSummary() != null ? r.getContentSummary() : "");
                out.add(dto);
            }
        }

        if (es != null) {
            for (CourseResourceDocument d : es) {
                if (out.size() >= limit) break;
                String url = canonicalUrl(d.getSourceUrl());
                String platform = normalizePlatform(d.getPlatform(), url);
                if (!isRelevantCandidate(topic, d.getTopic(), d.getTitle(), d.getContentSummary(), platform, url)) continue;
                ResourceClient.CourseResource dto = toClientDto(topic, d);
                if (dto == null) continue;
                String dtoUrl = canonicalUrl(dto.getUrl());
                if (dtoUrl == null) continue;
                dto.setUrl(dtoUrl);
                dto.setPlatform(normalizePlatform(dto.getPlatform(), dtoUrl));
                if (!seen.add(dtoUrl)) continue;
                String key = normalizeTitleKey(dto.getTitle(), topic, dto.getPlatform());
                if (!seenTitleKey.add(key)) continue;
                if (isNearDuplicateBase(normalizeTitleBase(dto.getTitle(), topic), seenBases)) continue;
                seenBases.add(normalizeTitleBase(dto.getTitle(), topic));
                out.add(dto);
            }
        }

        if (out.isEmpty()) {
            return defaultResources(topic);
        }

        int target = Math.min(limit, 6);
        if (out.size() < target) {
            List<ResourceClient.CourseResource> fill = defaultResources(topic);
            for (ResourceClient.CourseResource r : fill) {
                if (out.size() >= target) break;
                if (r == null || r.getUrl() == null) continue;
                String url = canonicalUrl(r.getUrl());
                if (url == null) continue;
                r.setUrl(url);
                r.setPlatform(normalizePlatform(r.getPlatform(), url));
                if (!seen.add(url)) continue;
                String key = normalizeTitleKey(r.getTitle(), topic, r.getPlatform());
                if (!seenTitleKey.add(key)) continue;
                if (isNearDuplicateBase(normalizeTitleBase(r.getTitle(), topic), seenBases)) continue;
                seenBases.add(normalizeTitleBase(r.getTitle(), topic));
                out.add(r);
            }
        }

        return out;
    }

    private List<String> findRelatedTopics(String q, int limit) {
        try {
            if (q == null || q.isBlank()) return List.of();
            return courseResourceMapper.selectDistinctTopicsLike(q, Math.max(1, Math.min(limit, 20)));
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<CourseResource> searchFromDb(String q, List<String> relatedTopics, int limit) {
        try {
            LambdaQueryWrapper<CourseResource> qw = new LambdaQueryWrapper<CourseResource>()
                    .orderByDesc(CourseResource::getCreatedAt);

            boolean hasTopics = relatedTopics != null && !relatedTopics.isEmpty();
            if (hasTopics) {
                qw.in(CourseResource::getTopic, relatedTopics);
            } else if (q != null && !q.isBlank()) {
                qw.like(CourseResource::getTopic, q).or().like(CourseResource::getTitle, q);
            } else {
                return List.of();
            }
            qw.last("LIMIT " + Math.max(1, Math.min(limit, 80)));
            return courseResourceMapper.selectList(qw);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<CourseResourceDocument> searchFromEs(String topic, int limit) {
        try {
            String queryText = topic != null ? topic.trim() : "";
            if (queryText.isBlank()) return List.of();
            NativeQuery query = NativeQuery.builder()
                    .withQuery(qb -> qb.multiMatch(m -> m
                            .query(queryText)
                            .fields("title^3", "topic^2", "contentSummary")
                            .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                            .operator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.Or)))
                    .withPageable(PageRequest.of(0, Math.max(1, Math.min(limit, 50))))
                    .build();

            return elasticsearchOperations.search(query, CourseResourceDocument.class)
                    .getSearchHits()
                    .stream()
                    .map(SearchHit::getContent)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private ResourceClient.ResourceAdviceResponse adviseFromCandidates(
            String topic,
            List<String> relatedTopics,
            List<CourseResourceDocument> esCandidates,
            List<CourseResource> dbCandidates) {
        String ctx = buildCandidateContextJson(topic, esCandidates, dbCandidates, 24);
        if (ctx == null || ctx.equals("[]")) return null;

        String topicJson;
        try {
            topicJson = objectMapper.writeValueAsString(relatedTopics != null ? relatedTopics : List.of());
        } catch (Exception e) {
            topicJson = "[]";
        }

        String dbStatsJson;
        try {
            List<CourseResource> safe = dbCandidates != null ? dbCandidates : List.of();
            Map<String, Long> byTopic = safe.stream()
                    .map(CourseResource::getTopic)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            Map<String, Long> byPlatform = safe.stream()
                    .map(CourseResource::getPlatform)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            java.util.HashMap<String, Object> m = new java.util.HashMap<>();
            m.put("byTopic", byTopic);
            m.put("byPlatform", byPlatform);
            dbStatsJson = objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            dbStatsJson = "{}";
        }

        String prompt = ""
                + "你是大学生学习规划与资源整合助手。\n"
                + "主题：" + topic + "\n\n"
                + "数据库中与主题相关的已有分类（JSON数组）：\n"
                + topicJson + "\n\n"
                + "数据库候选资源统计（JSON对象）：\n"
                + dbStatsJson + "\n\n"
                + "系统检索到的候选资源（信息可能不全，JSON数组，字段可能为空）：\n"
                + ctx + "\n\n"
                + "任务：\n"
                + "1) advice：用 200~400 字给出学习路径建议（入门->进阶）、每一步怎么学、常见坑与注意点。\n"
                + "2) resources：必须从候选资源中挑选 6 条（更精炼），每条需要 title/platform/url/summary。\n"
                + "   - 严禁凭空编造候选中不存在的具体视频/课程/文章。\n"
                + "   - summary 请写成“推荐理由 + 下一步行动”的形式，信息不全可以写“建议点击链接查看大纲/目录后决定是否学习”。\n"
                + "   - url 必须以 https:// 开头；如果候选 url 为空或不合法，仅允许输出“平台搜索链接”（如 Bilibili/GitHub/Coursera/知乎/Medium/Google 的搜索链接）。\n"
                + "只输出严格 JSON 对象，不要 Markdown，不要额外文字。\n"
                + "{\"advice\":\"...\",\"resources\":[{\"title\":\"...\",\"platform\":\"...\",\"url\":\"https://...\",\"summary\":\"...\"}]}\n";

        String text;
        try {
            text = CompletableFuture
                    .supplyAsync(() -> openAiCompatClient.complete(prompt))
                    .orTimeout(Math.max(5, ragTimeoutSeconds), TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            Throwable t = unwrapThrowable(e);
            log.warn("RAG 建议生成失败，topic={}, err={}", topic, t != null ? (t.getClass().getName() + ": " + t.getMessage()) : String.valueOf(e));
            return null;
        }

        String json = extractJsonObject(text);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("RAG 建议解析失败，topic={}, err={}", topic, String.valueOf(e));
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }

        ResourceClient.ResourceAdviceResponse resp = new ResourceClient.ResourceAdviceResponse();
        resp.setTopic(topic);
        resp.setAdvice(root.path("advice").asText(""));

        JsonNode arr = root.path("resources");
        if (arr == null || !arr.isArray()) {
            return null;
        }

        Set<String> candidateUrls = extractCandidateUrls(esCandidates, dbCandidates);
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenTitleKey = new HashSet<>();
        List<String> seenBases = new ArrayList<>();
        List<ResourceClient.CourseResource> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (n == null || !n.isObject()) continue;
            String title = n.path("title").asText(null);
            String platform = n.path("platform").asText(null);
            String url = n.path("url").asText(null);
            String summary = n.path("summary").asText(null);

            if (url == null || !url.startsWith("https://")) {
                url = buildSearchUrl(platform, topic);
            }
            if (url == null || !url.startsWith("https://")) continue;
            url = canonicalUrl(url);
            if (url == null || !url.startsWith("https://")) continue;
            if (!seenUrls.add(url)) continue;
            platform = normalizePlatform(platform, url);
            if (!candidateUrls.contains(url) && !isAllowedSearchUrl(url, topic)) {
                continue;
            }
            if (!isRelevantCandidate(topic, null, title, summary, platform, url)) {
                continue;
            }
            String key = normalizeTitleKey(title, topic, platform);
            if (!seenTitleKey.add(key)) continue;
            String base = normalizeTitleBase(title, topic);
            if (isNearDuplicateBase(base, seenBases)) continue;
            seenBases.add(base);

            ResourceClient.CourseResource dto = new ResourceClient.CourseResource();
            dto.setTitle(title != null && !title.isBlank() ? title : topic + " 学习资源");
            dto.setPlatform(platform);
            dto.setUrl(url);
            dto.setSummary(summary != null ? summary : "");
            out.add(dto);
        }
        if (out.isEmpty()) return null;
        resp.setResources(out);
        return resp;
    }

    private Throwable unwrapThrowable(Throwable t) {
        if (t == null) return null;
        if (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) return unwrapThrowable(t.getCause());
        if (t instanceof java.util.concurrent.ExecutionException && t.getCause() != null) return unwrapThrowable(t.getCause());
        return t;
    }

    private String buildCandidateContextJson(
            String topic,
            List<CourseResourceDocument> esCandidates,
            List<CourseResource> dbCandidates,
            int limit) {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            Set<String> seenUrl = new HashSet<>();

            Function<String, String> normalizeUrl = u -> {
                return canonicalUrl(u);
            };

            if (esCandidates != null) {
                for (CourseResourceDocument d : esCandidates) {
                    if (d == null) continue;
                    if (!isRelevantCandidate(topic, d.getTopic(), d.getTitle(), d.getContentSummary(), d.getPlatform(), d.getSourceUrl())) continue;
                    String url = normalizeUrl.apply(d.getSourceUrl());
                    if (url != null && !url.isBlank() && !seenUrl.add(url)) continue;

                    String t = d.getTitle();
                    String s = d.getContentSummary();
                    if ((url == null || url.isBlank()) && (t == null || t.isBlank()) && (s == null || s.isBlank())) continue;

                    java.util.HashMap<String, Object> m = new java.util.HashMap<>();
                    m.put("source", "es");
                    m.put("id", d.getId());
                    m.put("topic", limitText(d.getTopic(), 40));
                    m.put("title", limitText(d.getTitle(), 120));
                    m.put("platform", limitText(normalizePlatform(d.getPlatform(), d.getSourceUrl()), 30));
                    m.put("url", limitText(d.getSourceUrl(), 300));
                    m.put("summary", limitText(d.getContentSummary(), 260));
                    out.add(m);
                    if (out.size() >= limit) break;
                }
            }

            if (dbCandidates != null && out.size() < limit) {
                for (CourseResource r : dbCandidates) {
                    if (r == null) continue;
                    if (!isRelevantCandidate(topic, r.getTopic(), r.getTitle(), r.getContentSummary(), r.getPlatform(), r.getSourceUrl())) continue;
                    String url = normalizeUrl.apply(r.getSourceUrl());
                    if (url != null && !url.isBlank() && !seenUrl.add(url)) continue;

                    String t = r.getTitle();
                    String s = r.getContentSummary();
                    if ((url == null || url.isBlank()) && (t == null || t.isBlank()) && (s == null || s.isBlank())) continue;

                    java.util.HashMap<String, Object> m = new java.util.HashMap<>();
                    m.put("source", "db");
                    m.put("id", r.getId());
                    m.put("topic", limitText(r.getTopic(), 40));
                    m.put("title", limitText(r.getTitle(), 120));
                    m.put("platform", limitText(normalizePlatform(r.getPlatform(), r.getSourceUrl()), 30));
                    m.put("url", limitText(r.getSourceUrl(), 300));
                    m.put("summary", limitText(r.getContentSummary(), 260));
                    out.add(m);
                    if (out.size() >= limit) break;
                }
            }

            if (out.isEmpty()) return "[]";
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return "[]";
        }
    }

    private void upsertFromModel(String topic, List<ResourceClient.CourseResource> resources) {
        if (topic == null || topic.isBlank()) return;
        if (resources == null || resources.isEmpty()) return;

        for (ResourceClient.CourseResource r : resources) {
            if (r == null) continue;
            String url = canonicalUrl(r.getUrl());
            if (url == null || url.isBlank()) continue;

            CourseResource existing = courseResourceMapper.selectOne(new LambdaQueryWrapper<CourseResource>()
                    .eq(CourseResource::getTopic, topic)
                    .eq(CourseResource::getSourceUrl, url)
                    .last("LIMIT 1"));

            if (existing == null) {
                CourseResource cr = new CourseResource();
                cr.setTopic(topic);
                cr.setTitle(r.getTitle());
                cr.setPlatform(normalizePlatform(r.getPlatform(), url));
                cr.setSourceUrl(url);
                cr.setContentSummary(r.getSummary());
                cr.setCreatedAt(LocalDateTime.now());
                courseResourceMapper.insert(cr);
                upsertToEs(cr);
                continue;
            }

            boolean changed = false;
            if ((existing.getTitle() == null || existing.getTitle().isBlank()) && r.getTitle() != null && !r.getTitle().isBlank()) {
                existing.setTitle(r.getTitle());
                changed = true;
            }
            if (existing.getPlatform() == null || existing.getPlatform().isBlank() || isInvalidPlatform(existing.getPlatform())) {
                String p = normalizePlatform(r.getPlatform(), url);
                if (p != null && !p.isBlank() && !isInvalidPlatform(p)) {
                    existing.setPlatform(p);
                    changed = true;
                }
            }
            if ((existing.getContentSummary() == null || existing.getContentSummary().isBlank()) && r.getSummary() != null && !r.getSummary().isBlank()) {
                existing.setContentSummary(r.getSummary());
                changed = true;
            }
            if (changed) {
                courseResourceMapper.updateById(existing);
                upsertToEs(existing);
            }
        }
    }

    private ResourceClient.CourseResource toClientDto(String topic, CourseResourceDocument d) {
        if (d == null) return null;

        String url = canonicalUrl(d.getSourceUrl());
        if (url == null || !url.startsWith("https://")) {
            url = buildSearchUrl(d.getPlatform(), topic);
        }
        if (url == null || !url.startsWith("https://")) {
            return null;
        }

        ResourceClient.CourseResource dto = new ResourceClient.CourseResource();
        dto.setTitle(d.getTitle() != null && !d.getTitle().isBlank() ? d.getTitle() : topic + " 学习资源");
        dto.setPlatform(normalizePlatform(d.getPlatform(), url));
        dto.setUrl(url);
        dto.setSummary(d.getContentSummary() != null ? d.getContentSummary() : "");
        return dto;
    }

    private void upsertToEs(CourseResource cr) {
        try {
            CourseResourceDocument d = new CourseResourceDocument();
            d.setId(cr.getId());
            d.setTopic(cr.getTopic());
            d.setTitle(cr.getTitle());
            d.setPlatform(cr.getPlatform());
            d.setSourceUrl(cr.getSourceUrl());
            d.setContentSummary(cr.getContentSummary());
            d.setCreatedAtEpochMillis(cr.getCreatedAt() != null ? cr.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
            searchRepository.save(d);
        } catch (Exception ignored) {
        }
    }

    private void persistAiResources(String topic, List<ResourceClient.CourseResource> ai) {
        if (ai == null || ai.isEmpty()) return;
        for (ResourceClient.CourseResource r : ai) {
            if (r == null) continue;
            String url = canonicalUrl(r.getUrl());
            if (url == null || url.isBlank()) continue;

            Long existing = courseResourceMapper.selectCount(new LambdaQueryWrapper<CourseResource>()
                    .eq(CourseResource::getTopic, topic)
                    .eq(CourseResource::getSourceUrl, url));
            if (existing != null && existing > 0) continue;

            CourseResource cr = new CourseResource();
            cr.setTopic(topic);
            cr.setTitle(r.getTitle());
            cr.setPlatform(normalizePlatform(r.getPlatform(), url));
            cr.setSourceUrl(url);
            cr.setContentSummary(r.getSummary());
            cr.setCreatedAt(LocalDateTime.now());
            courseResourceMapper.insert(cr);
            upsertToEs(cr);
        }
    }

    private String canonicalUrl(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.isEmpty()) return null;
        if (!u.startsWith("http://") && !u.startsWith("https://")) return u;
        try {
            URI uri = URI.create(u);
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "https";
            if (scheme.equals("http")) scheme = "https";

            String host = uri.getHost();
            host = host != null ? host.toLowerCase() : null;
            String path = uri.getRawPath();
            path = path != null ? path : "";

            if (host != null && (host.equals("b23.tv") || host.endsWith(".b23.tv"))) {
                java.util.regex.Matcher m = BILIBILI_BV.matcher(path);
                if (m.find()) {
                    return "https://www.bilibili.com/video/" + m.group(1);
                }
                String p = path.startsWith("/") ? path.substring(1) : path;
                if (p.startsWith("BV") && p.length() >= 10) {
                    int end = 0;
                    while (end < p.length()) {
                        char c = p.charAt(end);
                        if (Character.isLetterOrDigit(c)) {
                            end++;
                        } else {
                            break;
                        }
                    }
                    String bv = p.substring(0, end);
                    return "https://www.bilibili.com/video/" + bv;
                }
                return "https://b23.tv" + path;
            }

            if (host != null && (host.equals("bilibili.com") || host.endsWith(".bilibili.com"))) {
                java.util.regex.Matcher m = BILIBILI_BV.matcher(path);
                if (m.find()) {
                    return "https://www.bilibili.com/video/" + m.group(1);
                }
                if (path.startsWith("/video/")) {
                    String p = path;
                    while (p.endsWith("/") && p.length() > "/video/".length()) {
                        p = p.substring(0, p.length() - 1);
                    }
                    return "https://www.bilibili.com" + p;
                }
            }

            if (host == null || host.isBlank()) return u;
            String normalized = scheme + "://" + host;
            if (!path.isBlank()) normalized += path;
            String query = uri.getRawQuery();
            if (query != null && !query.isBlank()) {
                normalized += "?" + query;
            }
            if (normalized.endsWith("/") && normalized.length() > (scheme + "://" + host + "/").length()) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized.isBlank() ? u : normalized;
        } catch (Exception e) {
            int qm = u.indexOf('?');
            if (qm > 0 && u.contains("bilibili.com/video/")) {
                return u.substring(0, qm);
            }
            return u;
        }
    }

    private List<ResourceClient.CourseResource> dedupeResources(String topic, List<ResourceClient.CourseResource> in, int limit) {
        if (in == null || in.isEmpty()) return List.of();
        List<ResourceClient.CourseResource> out = new ArrayList<>();
        Set<String> seenUrl = new HashSet<>();
        Set<String> seenTitleKey = new HashSet<>();
        List<String> seenBases = new ArrayList<>();
        for (ResourceClient.CourseResource r : in) {
            if (r == null) continue;
            if (out.size() >= Math.max(1, limit)) break;
            String url = canonicalUrl(r.getUrl());
            if (url == null || !url.startsWith("https://")) continue;
            if (!seenUrl.add(url)) continue;
            String title = r.getTitle();
            String platform = normalizePlatform(r.getPlatform(), url);
            String summary = r.getSummary();
            if (!isRelevantCandidate(topic, null, title, summary, platform, url)) continue;
            String key = normalizeTitleKey(title, topic, platform);
            if (!seenTitleKey.add(key)) continue;
            String base = normalizeTitleBase(title, topic);
            if (isNearDuplicateBase(base, seenBases)) continue;
            seenBases.add(base);

            ResourceClient.CourseResource dto = new ResourceClient.CourseResource();
            dto.setTitle(title != null && !title.isBlank() ? title : topic + " 学习资源");
            dto.setPlatform(platform);
            dto.setUrl(url);
            dto.setSummary(summary != null ? summary : "");
            out.add(dto);
        }
        return out;
    }

    private boolean isInvalidPlatform(String platform) {
        if (platform == null) return true;
        String p = platform.trim();
        if (p.isBlank()) return true;
        if (p.length() <= 1) return true;
        return DIGITS_ONLY.matcher(p).matches();
    }

    private String normalizePlatform(String platform, String url) {
        String p = platform != null ? platform.trim() : "";
        if (isInvalidPlatform(p)) {
            String fromUrl = platformFromUrl(url);
            return fromUrl != null ? fromUrl : "推荐";
        }
        String lowered = p.toLowerCase();
        if (lowered.contains("b站") || lowered.contains("bilibili")) return "B站";
        if (lowered.contains("github")) return "GitHub";
        if (lowered.contains("zhihu") || lowered.contains("知乎")) return "知乎";
        if (lowered.contains("coursera")) return "Coursera";
        if (lowered.contains("edx")) return "edX";
        if (lowered.contains("medium")) return "Medium";
        if (lowered.contains("google")) return "Google";
        if (p.length() > 30) return p.substring(0, 30);
        return p;
    }

    private String platformFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            host = host != null ? host.toLowerCase() : "";
            if (host.contains("bilibili.com") || host.contains("b23.tv")) return "B站";
            if (host.contains("github.com")) return "GitHub";
            if (host.contains("zhihu.com")) return "知乎";
            if (host.contains("coursera.org")) return "Coursera";
            if (host.contains("edx.org")) return "edX";
            if (host.contains("medium.com")) return "Medium";
            if (host.contains("google.com")) return "Google";
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ")
                .trim();
    }

    private String limitText(String s, int maxLen) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() <= maxLen) return t;
        return t.substring(0, maxLen);
    }

    private List<ResourceClient.CourseResource> recommendByAi(String topic) {
        String prompt = ""
                + "你是学习资源推荐助手。请为主题推荐 8 条学习资源入口。\n"
                + "重要：url 必须是真实平台的搜索链接（如 B站搜索、GitHub搜索、知乎搜索等），不要编造具体课程URL。\n"
                + "格式：https://search.bilibili.com/all?keyword=关键词 或 https://github.com/search?q=关键词 等。\n"
                + "平台优先：官方文档、GitHub、B站、Coursera/edX、知乎、博客园。\n"
                + "只输出严格 JSON 数组，不要 Markdown，不要额外文字。\n"
                + "[{\"title\":\"...\",\"platform\":\"...\",\"url\":\"https://...\",\"summary\":\"...\"}]\n\n"
                + "主题：" + topic;

        String text;
        try {
            text = CompletableFuture
                    .supplyAsync(() -> openAiCompatClient.complete(prompt))
                    .orTimeout(6, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            return List.of();
        }
        String json = extractJsonArray(text);
        JsonNode arr;
        try {
            arr = objectMapper.readTree(json);
        } catch (Exception e) {
            return List.of();
        }
        if (arr == null || !arr.isArray()) {
            return List.of();
        }

        List<ResourceClient.CourseResource> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (n == null || !n.isObject()) continue;
            String title = n.path("title").asText(null);
            String platform = n.path("platform").asText(null);
            String url = n.path("url").asText(null);
            String summary = n.path("summary").asText(null);
            if (url == null || !url.startsWith("https://")) {
                url = buildSearchUrl(platform, topic);
            }
            if (url == null || !url.startsWith("https://")) {
                continue;
            }
            ResourceClient.CourseResource dto = new ResourceClient.CourseResource();
            dto.setTitle(title != null && !title.isBlank() ? title : topic + " 学习资源");
            dto.setPlatform(platform != null && !platform.isBlank() ? platform : "推荐");
            dto.setUrl(url);
            dto.setSummary(summary != null ? summary : "");
            out.add(dto);
        }
        return out;
    }

    private String buildSearchUrl(String platform, String topic) {
        String q = URLEncoder.encode(topic, StandardCharsets.UTF_8);
        String p = platform != null ? platform.toLowerCase() : "";
        if (p.contains("github")) {
            return "https://github.com/search?q=" + q;
        }
        if (p.contains("b站") || p.contains("bilibili")) {
            return "https://search.bilibili.com/all?keyword=" + q;
        }
        if (p.contains("coursera")) {
            return "https://www.coursera.org/search?query=" + q;
        }
        if (p.contains("edx")) {
            return "https://www.edx.org/search?q=" + q;
        }
        if (p.contains("知乎")) {
            return "https://www.zhihu.com/search?q=" + q;
        }
        if (p.contains("medium")) {
            return "https://medium.com/search?q=" + q;
        }
        return "https://www.google.com/search?q=" + q;
    }

    private String extractJsonArray(String text) {
        if (text == null) return "[]";
        String s = text.trim();
        int first = s.indexOf('[');
        int last = s.lastIndexOf(']');
        if (first >= 0 && last > first) {
            return s.substring(first, last + 1).trim();
        }
        return s;
    }

    private String extractJsonObject(String text) {
        if (text == null) return "{}";
        String s = text.trim();
        int first = s.indexOf('{');
        int last = s.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return s.substring(first, last + 1).trim();
        }
        return s;
    }

    private Set<String> extractCandidateUrls(List<CourseResourceDocument> esCandidates, List<CourseResource> dbCandidates) {
        Set<String> out = new HashSet<>();
        if (esCandidates != null) {
            for (CourseResourceDocument d : esCandidates) {
                if (d == null) continue;
                String url = canonicalUrl(d.getSourceUrl());
                if (url != null && url.startsWith("https://")) {
                    out.add(url);
                }
            }
        }
        if (dbCandidates != null) {
            for (CourseResource r : dbCandidates) {
                if (r == null) continue;
                String url = canonicalUrl(r.getSourceUrl());
                if (url != null && url.startsWith("https://")) {
                    out.add(url);
                }
            }
        }
        return out;
    }

    private boolean isAllowedSearchUrl(String url, String topic) {
        if (url == null || !url.startsWith("https://")) return false;
        String t = topic != null ? topic.trim() : "";
        if (t.isBlank()) return false;
        String enc = URLEncoder.encode(t, StandardCharsets.UTF_8);
        if (!url.contains(enc)) return false;
        return url.startsWith("https://search.bilibili.com/")
                || url.startsWith("https://github.com/search")
                || url.startsWith("https://www.coursera.org/search")
                || url.startsWith("https://www.edx.org/search")
                || url.startsWith("https://www.zhihu.com/search")
                || url.startsWith("https://medium.com/search")
                || url.startsWith("https://www.google.com/search");
    }

    private boolean isCsTopic(String topic) {
        String s = normalizeText(topic);
        if (s.isBlank()) return false;
        String[] keys = new String[] { "java", "python", "go", "c++", "cpp", "javascript", "js", "typescript", "ts", "spring", "mysql", "redis", "mq", "kafka", "elasticsearch", "es", "linux", "docker", "k8s", "kubernetes", "分布式", "数据库", "算法", "数据结构", "网络", "操作系统", "后端", "前端", "微服务" };
        for (String k : keys) {
            if (s.contains(normalizeText(k))) return true;
        }
        return false;
    }

    private String normalizeTitleKey(String title, String topic, String platform) {
        String t = title != null ? title : (topic != null ? topic : "");
        java.util.regex.Matcher m = TITLE_SPLIT.matcher(t);
        if (m.find()) {
            int cut = m.start();
            if (cut > 0) t = t.substring(0, cut);
        }
        String x = TITLE_NOISE.matcher(t).replaceAll(" ");
        x = NON_WORD.matcher(x).replaceAll("");
        x = DIGITS.matcher(x).replaceAll("");
        x = x.toLowerCase();
        if (x.length() > 120) x = x.substring(0, 120);
        String p = platform != null ? platform.trim().toLowerCase() : "";
        return p + "|" + x;
    }

    private boolean isRelevantCandidate(String query, String docTopic, String title, String summary, String platform, String url) {
        String q = normalizeText(query);
        if (q.isBlank()) return false;
        String t = normalizeText(title);
        String s = normalizeText(summary);
        String dt = normalizeText(docTopic);
        if (q.length() >= 4) {
            String first2 = q.substring(0, 2);
            String last2 = q.substring(q.length() - 2);
            boolean hitFirst = (!t.isBlank() && t.contains(first2)) || (!s.isBlank() && s.contains(first2));
            boolean hitLast = (!t.isBlank() && t.contains(last2)) || (!s.isBlank() && s.contains(last2));
            boolean hitEssential = q.length() >= 6 ? (hitFirst && hitLast) : (hitFirst || hitLast);
            if (!hitEssential) return false;
        }
        int grams = bigramMatchCount(q, t, s);
        if (q.length() >= 6 && grams < 3 && (t.isBlank() || !t.contains(q)) && (s.isBlank() || !s.contains(q))) {
            return false;
        }
        if (q.length() >= 4 && q.length() < 6 && grams < 2 && (t.isBlank() || !t.contains(q)) && (s.isBlank() || !s.contains(q))) {
            return false;
        }
        int score = 0;
        if (!dt.isBlank() && (dt.equals(q) || dt.contains(q) || q.contains(dt))) score += 2;
        if (!t.isBlank() && t.contains(q)) score += 4;
        if (!s.isBlank() && s.contains(q)) score += 2;
        if (q.length() >= 4 && grams >= 2) score += 3;
        if (q.length() < 4 && grams >= 1) score += 3;
        double sim = Math.max(bigramSimilarity(q, t), bigramSimilarity(q, s));
        if (sim >= 0.22) score += 2;
        if (sim >= 0.35) score += 2;
        if (score >= 4) return true;
        if (t.isBlank() && s.isBlank() && platform != null && url != null) {
            return false;
        }
        return false;
    }

    private String normalizeTitleBase(String title, String topic) {
        String t = title != null ? title : (topic != null ? topic : "");
        java.util.regex.Matcher m = TITLE_SPLIT.matcher(t);
        if (m.find()) {
            int cut = m.start();
            if (cut > 0) t = t.substring(0, cut);
        }
        String x = TITLE_NOISE.matcher(t).replaceAll(" ");
        x = NON_WORD.matcher(x).replaceAll("");
        x = DIGITS.matcher(x).replaceAll("");
        x = x.toLowerCase();
        if (x.length() > 180) x = x.substring(0, 180);
        return x;
    }

    private boolean isNearDuplicateBase(String base, List<String> bases) {
        if (base == null) return true;
        String b = base.trim();
        if (b.isEmpty()) return true;
        for (String e : bases) {
            if (e == null || e.isBlank()) continue;
            if (e.equals(b)) return true;
            if (e.length() >= 12 && b.startsWith(e)) return true;
            if (b.length() >= 12 && e.startsWith(b)) return true;
        }
        return false;
    }

    private int bigramMatchCount(String q, String t, String s) {
        if (q == null || q.isBlank()) return 0;
        String a = t != null ? t : "";
        String b = s != null ? s : "";
        if (q.length() < 2) {
            return (a.contains(q) || b.contains(q)) ? 1 : 0;
        }
        java.util.HashSet<String> grams = new java.util.HashSet<>();
        for (int i = 0; i < q.length() - 1; i++) {
            grams.add(q.substring(i, i + 2));
        }
        int hit = 0;
        for (String g : grams) {
            if (a.contains(g) || b.contains(g)) {
                hit++;
            }
        }
        return hit;
    }

    private String normalizeText(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase();
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
                continue;
            }
            if (c >= 0x4E00 && c <= 0x9FFF) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private double bigramSimilarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        String x = a.trim();
        String y = b.trim();
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        if (x.length() < 2 || y.length() < 2) {
            return y.contains(x) ? 1.0 : 0.0;
        }
        java.util.HashMap<String, Integer> mx = new java.util.HashMap<>();
        int nx = 0;
        for (int i = 0; i < x.length() - 1; i++) {
            String g = x.substring(i, i + 2);
            mx.put(g, mx.getOrDefault(g, 0) + 1);
            nx++;
        }
        int inter = 0;
        int ny = 0;
        for (int i = 0; i < y.length() - 1; i++) {
            String g = y.substring(i, i + 2);
            Integer c = mx.get(g);
            if (c != null && c > 0) {
                inter++;
                mx.put(g, c - 1);
            }
            ny++;
        }
        if (nx + ny == 0) return 0.0;
        return (2.0 * inter) / (nx + ny);
    }

    private List<ResourceClient.CourseResource> defaultResources(String topic) {
        String t = topic != null ? topic.trim() : "";
        if (t.isBlank()) {
            t = "学习";
        }
        List<ResourceClient.CourseResource> out = new ArrayList<>();

        out.add(make("官方文档/规范 搜索：" + t, "Google", "https://www.google.com/search?q=" + URLEncoder.encode(t + " 官方文档", StandardCharsets.UTF_8), "优先找官方文档与权威资料"));
        out.add(make("GitHub 搜索：" + t, "GitHub", "https://github.com/search?q=" + URLEncoder.encode(t, StandardCharsets.UTF_8), "找开源项目/示例/最佳实践"));
        out.add(make("B站 搜索：" + t, "Bilibili", "https://search.bilibili.com/all?keyword=" + URLEncoder.encode(t, StandardCharsets.UTF_8), "适合入门视频与实战课"));
        out.add(make("知乎 搜索：" + t, "知乎", "https://www.zhihu.com/search?q=" + URLEncoder.encode(t, StandardCharsets.UTF_8), "适合概念梳理与经验贴"));
        out.add(make("Coursera 搜索：" + t, "Coursera", "https://www.coursera.org/search?query=" + URLEncoder.encode(t, StandardCharsets.UTF_8), "系统化课程"));
        out.add(make("edX 搜索：" + t, "edX", "https://www.edx.org/search?q=" + URLEncoder.encode(t, StandardCharsets.UTF_8), "系统化课程"));
        out.add(make("Medium 搜索：" + t, "Medium", "https://medium.com/search?q=" + URLEncoder.encode(t, StandardCharsets.UTF_8), "英文技术文章"));
        out.add(make("Google 搜索：" + t, "Google", "https://www.google.com/search?q=" + URLEncoder.encode(t + " 教程", StandardCharsets.UTF_8), "泛检索：教程/博客/资料合集"));

        return out;
    }

    private ResourceClient.CourseResource make(String title, String platform, String url, String summary) {
        ResourceClient.CourseResource dto = new ResourceClient.CourseResource();
        dto.setTitle(title);
        dto.setPlatform(platform);
        dto.setUrl(url);
        dto.setSummary(summary);
        return dto;
    }
    @Scheduled(initialDelayString = "${smartplanner.crawler.bilibili.initial-delay-ms:120000}", fixedDelayString = "${smartplanner.crawler.bilibili.interval-ms:21600000}")
    public void scheduledBilibiliCrawl() {
        if (!bilibiliCrawlerEnabled) return;
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
                Result<java.util.List<String>> topicsResult = goalClient.getDistinctTopics();
                java.util.List<String> goalTopics = topicsResult != null ? topicsResult.getData() : java.util.List.of();
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
            if (topics.isEmpty()) return;

            log.info("Bilibili crawler started: {} topics, limit {} per topic", topics.size(), bilibiliCrawlerPerTopicLimit);
            int totalNew = 0;
            for (String topic : topics) {
                try {
                    List<ResourceClient.CourseResource> candidates = fetchBilibiliCandidates(topic, topic, bilibiliCrawlerPerTopicLimit);
                    for (ResourceClient.CourseResource c : candidates) {
                        if (saveIfNew(topic, c)) totalNew++;
                    }
                } catch (Exception e) {
                    log.warn("Crawl failed for topic {}: {}", topic, e.getMessage());
                }
            }
            log.info("Bilibili crawler finished: {} new resources saved", totalNew);
        } finally {
            crawlerRunning.set(false);
        }
    }

    private boolean saveIfNew(String topic, ResourceClient.CourseResource c) {
        if (topic == null || c == null || c.getUrl() == null) return false;
        // Check duplicate by URL
        Long count = courseResourceMapper.selectCount(
                new LambdaQueryWrapper<CourseResource>().eq(CourseResource::getSourceUrl, c.getUrl()));
        if (count != null && count > 0) return false;

        CourseResource entity = new CourseResource();
        entity.setTopic(topic);
        entity.setTitle(c.getTitle());
        entity.setSourceUrl(c.getUrl());
        entity.setPlatform(c.getPlatform());
        entity.setContentSummary(c.getSummary());
        entity.setCreatedAt(LocalDateTime.now());
        courseResourceMapper.insert(entity);

        // Index to ES
        try {
            CourseResourceDocument doc = new CourseResourceDocument();
            doc.setId(entity.getId());
            doc.setTopic(topic);
            doc.setTitle(entity.getTitle());
            doc.setPlatform(entity.getPlatform());
            doc.setSourceUrl(entity.getSourceUrl());
            doc.setContentSummary(entity.getContentSummary());
            doc.setCreatedAtEpochMillis(entity.getCreatedAt() != null ? entity.getCreatedAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli() : System.currentTimeMillis());
            searchRepository.save(doc);
        } catch (Exception e) {
            log.warn("Failed to index resource to ES: {}", e.getMessage());
        }
        return true;
    }

    private String httpGetTextWithUA(String url, String userAgent, String referer, String origin) {
        int maxRetries = 2;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("Referer", referer);
                if (origin != null) conn.setRequestProperty("Origin", origin);
                conn.setRequestProperty("Accept", "application/json, text/plain, */*");
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    byte[] bytes = conn.getInputStream().readAllBytes();
                    return new String(bytes, StandardCharsets.UTF_8);
                }
                if (code == 429 || code >= 500) {
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

    List<ResourceClient.CourseResource> fetchBilibiliCandidates(String query, String topic, int limit) {
        String q = query != null ? query.trim() : "";
        if (q.isEmpty()) return List.of();
        String apiUrl = "https://api.bilibili.com/x/web-interface/search/all/v2?keyword=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8);
        String json = httpGetTextWithUA(apiUrl,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "https://www.bilibili.com/",
                "https://www.bilibili.com");
        if (json == null || json.isBlank()) return List.of();
        List<ResourceClient.CourseResource> out = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.path("code").asInt() != 0) return out;
            JsonNode result = root.path("data").path("result");
            if (!result.isArray()) return out;
            outer:
            for (JsonNode category : result) {
                if (out.size() >= limit) break;
                if (!"video".equals(category.path("result_type").asText())) continue;
                JsonNode items = category.path("data");
                if (!items.isArray()) continue;
                for (JsonNode item : items) {
                    if (out.size() >= limit) continue outer;
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
                    // Build richer summary
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
                }
            }
        } catch (Exception e) {
            log.warn("Bilibili crawl failed: query={}, err={}", q, e.getMessage());
        }
        return out;
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

}
