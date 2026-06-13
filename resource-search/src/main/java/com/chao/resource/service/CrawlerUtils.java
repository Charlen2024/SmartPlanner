package com.chao.resource.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.client.ResourceClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chao.resource.entity.CourseResource;
import com.chao.resource.mapper.CourseResourceMapper;
import com.chao.resource.search.CourseResourceDocument;
import com.chao.resource.search.CourseResourceSearchRepository;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared static utilities used by all platform crawlers:
 * quality filtering, DB/ES persistence, embedding generation, HTTP helpers, query expansion.
 */
public final class CrawlerUtils {

    private CrawlerUtils() {}

    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");
    private static final Pattern HEX_HASH = Pattern.compile("^[0-9a-fA-F]{32,}$");
    private static final Pattern HEX_HASH_SUFFIX = Pattern.compile(".*_[0-9a-fA-F]{16,}$");
    private static final Pattern CJK_CHAR = Pattern.compile("[\\u4E00-\\u9FFF]");

    // ---- quality filter ----

    public static boolean isValidTitle(String title, String topic) {
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

    public static boolean isContentRelevantToTopic(String topic, String title, String summary) {
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

    // ---- persistence ----

    /**
     * Save a candidate resource to DB + ES, with dedup and quality filtering.
     * Returns true if the resource was newly saved.
     */
    public static boolean saveIfNew(String topic, ResourceClient.CourseResource c,
                                     CourseResourceMapper mapper,
                                     CourseResourceSearchRepository searchRepo,
                                     EmbeddingModel embeddingModel,
                                     boolean qualityFilterEnabled) {
        if (topic == null || c == null || c.getUrl() == null) return false;
        Long count = mapper.selectCount(
                new LambdaQueryWrapper<CourseResource>().eq(CourseResource::getSourceUrl, c.getUrl()));
        if (count != null && count > 0) return false;
        if (!isValidTitle(c.getTitle(), topic)) return false;
        if (qualityFilterEnabled && !isContentRelevantToTopic(topic, c.getTitle(), c.getSummary())) return false;
        return saveDirect(topic, c, mapper, searchRepo, embeddingModel);
    }

    /**
     * Save directly (caller already performed dedup and quality checks).
     */
    public static boolean saveDirect(String topic, ResourceClient.CourseResource c,
                                      CourseResourceMapper mapper,
                                      CourseResourceSearchRepository searchRepo,
                                      EmbeddingModel embeddingModel) {
        CourseResource entity = new CourseResource();
        entity.setTopic(topic);
        entity.setTitle(c.getTitle());
        entity.setSourceUrl(c.getUrl());
        entity.setPlatform(c.getPlatform());
        entity.setContentSummary(c.getSummary());
        entity.setCreatedAt(LocalDateTime.now());
        mapper.insert(entity);
        try {
            CourseResourceDocument doc = new CourseResourceDocument();
            doc.setId(entity.getId());
            doc.setTopic(topic);
            doc.setTitle(entity.getTitle());
            doc.setPlatform(entity.getPlatform());
            doc.setSourceUrl(entity.getSourceUrl());
            doc.setContentSummary(entity.getContentSummary());
            doc.setCreatedAtEpochMillis(entity.getCreatedAt() != null
                    ? entity.getCreatedAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
                    : System.currentTimeMillis());
            generateAndSetEmbedding(doc, embeddingModel);
            searchRepo.save(doc);
        } catch (Exception e) {
            // ES index failure is non-fatal; DB record already persisted
        }
        return true;
    }

    // ---- embedding ----

    public static void generateAndSetEmbedding(CourseResourceDocument doc, EmbeddingModel embeddingModel) {
        if (embeddingModel == null) return;
        try {
            String text = buildEmbeddingText(doc.getTopic(), doc.getTitle(), doc.getContentSummary());
            if (text.isBlank()) return;
            float[] vector = embeddingModel.embed(text);
            if (vector != null && vector.length > 0) {
                doc.setEmbedding(vector);
            }
        } catch (Exception e) {
            // non-fatal
        }
    }

    public static String buildEmbeddingText(String topic, String title, String summary) {
        StringBuilder sb = new StringBuilder();
        if (topic != null && !topic.isBlank()) sb.append(topic).append(" ");
        if (title != null && !title.isBlank()) sb.append(title).append(" ");
        if (summary != null && !summary.isBlank()) sb.append(summary);
        String result = sb.toString().trim();
        if (result.length() > 6000) result = result.substring(0, 6000);
        return result;
    }

    // ---- query expansion ----

    /**
     * Build a list of search queries from a topic, adding suffixes for broader coverage.
     */
    public static List<String> buildSearchQueries(String topic, String suffixList) {
        List<String> queries = new ArrayList<>();
        queries.add(topic);
        boolean cjk = isCJK(topic);
        if (cjk) {
            if (topic.length() >= 2 && topic.length() <= 4 && !topic.contains(" ")) {
                String t = topic.trim();
                for (int i = 1; i < t.length(); i++) {
                    String reordered = t.substring(i) + t.substring(0, i);
                    if (!reordered.equals(t)) queries.add(reordered);
                }
            }
            if (suffixList != null && !suffixList.isBlank()) {
                for (String suffix : suffixList.split(",")) {
                    String s = suffix.trim();
                    if (!s.isEmpty()) queries.add(topic + s);
                }
            }
        } else {
            String[] enSuffixes = {"tutorial", "basics", "crash course", "project",
                    "full course", "beginner", "advanced", "interview"};
            for (String s : enSuffixes) {
                queries.add(topic + " " + s);
            }
        }
        queries.add(topic + " 2026");
        queries.add(topic + (cjk ? " 最新" : " latest"));
        return queries;
    }

    // ---- HTTP ----

    /**
     * Perform an HTTP GET with retries. Returns body on 2xx, null on failure.
     */
    public static String httpGet(RestTemplate restTemplate, String url, HttpHeaders headers, int maxRetries) {
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                ResponseEntity<String> resp = restTemplate.exchange(
                        URI.create(url), HttpMethod.GET, entity, String.class);
                if (resp.getStatusCode().is2xxSuccessful()) {
                    return resp.getBody();
                }
                if ((resp.getStatusCodeValue() == 429 || resp.getStatusCode().is5xxServerError())
                        && attempt < maxRetries) {
                    sleep((attempt + 1) * 1000L);
                    continue;
                }
                return null;
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    sleep((attempt + 1) * 500L);
                } else {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Parse a JSON response body using the given ObjectMapper.
     */
    public static <T> T parseJson(ObjectMapper om, String json, Class<T> clazz) {
        if (json == null || json.isBlank()) return null;
        try {
            return om.readValue(json, clazz);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- misc ----

    public static boolean isCJK(String s) {
        return ResourceTextUtils.isCJK(s);
    }

    public static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /**
     * Compact a summary string to maxLen chars.
     */
    public static String compactSummary(String s, int maxLen) {
        if (s == null || s.isBlank()) return "";
        String stripped = s.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
        if (stripped.length() <= maxLen) return stripped;
        return stripped.substring(0, maxLen - 3) + "...";
    }
}
