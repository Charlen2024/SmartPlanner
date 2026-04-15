package com.chao.resource.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.client.ResourceClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chao.resource.entity.CourseResource;
import com.chao.resource.mapper.CourseResourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceService {

    private final ObjectProvider<EmbeddingClient> embeddingClientProvider;
    private final CourseResourceMapper courseResourceMapper;
    private final OpenAiCompatClient openAiCompatClient;
    private final ObjectMapper objectMapper;

    public CourseResource createResource(String topic, String title, String platform, String url, String summary) {
        CourseResource cr = new CourseResource();
        cr.setTopic(topic);
        cr.setTitle(title);
        cr.setPlatform(platform);
        cr.setSourceUrl(url);
        cr.setContentSummary(summary);
        cr.setCreatedAt(LocalDateTime.now());
        courseResourceMapper.insert(cr);
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
            if (topic != null && !topic.isBlank()) {
                try {
                    List<ResourceClient.CourseResource> ai = recommendByAi(topic.trim());
                    if (ai != null && !ai.isEmpty()) {
                        return ai;
                    }
                } catch (Exception ignored) {
                }
            }

            List<CourseResource> dbResources = courseResourceMapper.selectList(new LambdaQueryWrapper<CourseResource>()
                    .like(CourseResource::getTopic, topic)
                    .or()
                    .like(CourseResource::getTitle, topic));

            List<ResourceClient.CourseResource> out = dbResources.stream().map(r -> {
                ResourceClient.CourseResource dto = new ResourceClient.CourseResource();
                dto.setTitle(r.getTitle());
                dto.setPlatform(r.getPlatform());
                dto.setUrl(r.getSourceUrl());
                dto.setSummary(r.getContentSummary());
                return dto;
            }).collect(Collectors.toList());

            if (out.isEmpty()) {
                return defaultResources(topic);
            }
            return out;
        } catch (Exception e) {
            log.error("检索资源失败", e);
            return defaultResources(topic);
        }
    }

    private List<ResourceClient.CourseResource> recommendByAi(String topic) {
        String prompt = ""
                + "你是学习资源推荐助手。请为主题生成 8 条真实可打开的资源入口。\n"
                + "要求：url 必须是可访问的真实网站链接（允许是站内搜索链接），必须以 https:// 开头。\n"
                + "平台优先：官方文档、GitHub、B站、Coursera/edX、Medium/知乎、博客。\n"
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
}
