package com.chao.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.dto.GoalTaskDto;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TaskAdviceAiService {
    private final OpenAiCompatClient openAiCompatClient;
    private final ObjectMapper objectMapper;

    public TaskAdviceAiService(OpenAiCompatClient openAiCompatClient, ObjectMapper objectMapper) {
        this.openAiCompatClient = openAiCompatClient;
        this.objectMapper = objectMapper;
    }

    public Map<Long, String> advise(List<GoalTaskDto> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return Map.of();
        }
        String prompt = buildPrompt(tasks);
        String text = openAiCompatClient.complete(prompt);
        return parse(text);
    }

    private String buildPrompt(List<GoalTaskDto> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是学习教练。为每个任务给出 1-2 句可执行建议（比如学习顺序、方法、资料关键字、避免误区）。\n");
        sb.append("只输出严格 JSON 对象，key 为 taskId（数字），value 为建议字符串。不要 Markdown，不要额外文字。\n\n");
        sb.append("任务列表：\n");
        for (GoalTaskDto t : tasks) {
            if (t == null || t.getId() == null) continue;
            sb.append("- taskId=").append(t.getId()).append(", title=").append(safe(t.getTitle()));
            if (t.getDescription() != null && !t.getDescription().isBlank()) {
                sb.append(", desc=").append(safe(t.getDescription()));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String safe(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", " ").trim();
    }

    private Map<Long, String> parse(String text) {
        try {
            String json = extractJson(text);
            JsonNode root = objectMapper.readTree(json);
            Map<Long, String> out = new HashMap<>();
            root.fields().forEachRemaining(e -> {
                try {
                    Long id = Long.valueOf(e.getKey());
                    String v = e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString();
                    out.put(id, v);
                } catch (Exception ignored) {
                }
            });
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        String s = text.trim();
        int first = s.indexOf('{');
        int last = s.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return s.substring(first, last + 1).trim();
        }
        return s;
    }
}

