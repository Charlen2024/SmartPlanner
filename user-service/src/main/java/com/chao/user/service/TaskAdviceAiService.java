package com.chao.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.dto.GoalTaskDto;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;

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

    public ScheduleAdviceResponse adviseSchedules(List<ScheduleAdviceItem> items, String moodHint) {
        if (items == null || items.isEmpty()) {
            return new ScheduleAdviceResponse("", Map.of());
        }
        String prompt = buildSchedulePrompt(items, moodHint);
        String text = openAiCompatClient.complete(prompt);
        return parseScheduleAdvice(text);
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

    private String buildSchedulePrompt(List<ScheduleAdviceItem> items, String moodHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是学习教练。你将收到用户“今天的排程任务列表”。\n");
        sb.append("要求：\n");
        sb.append("1) 建议必须与排程一致：不要要求用户改变开始/结束时间，不要重新排序。\n");
        sb.append("2) 每个任务给两句话：start（如何在 10-15 分钟内启动的第一步）和 done（该时间段内可验收的完成标准）。\n");
        sb.append("3) 额外给一句 header（今天的小建议），语气自然，不要出现“适配/策略/算法”等词。\n");
        sb.append("4) 只输出严格 JSON，不要 Markdown，不要额外文字。\n");
        sb.append("\n");

        if (moodHint != null && !moodHint.isBlank()) {
            sb.append("用户最近心情信息（仅用于调整学习方式，不要做诊断）：").append(moodHint.trim()).append("\n\n");
        }

        sb.append("排程任务列表（JSON数组，每项包含 taskId/title/description/startTime/endTime/timeBudgetMinutes）：\n");
        try {
            List<Map<String, Object>> arr = new ArrayList<>();
            for (ScheduleAdviceItem it : items) {
                if (it == null || it.taskId == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("taskId", it.taskId);
                m.put("title", it.title);
                m.put("description", it.description);
                m.put("startTime", it.startTime);
                m.put("endTime", it.endTime);
                m.put("timeBudgetMinutes", it.timeBudgetMinutes);
                arr.add(m);
            }
            sb.append(objectMapper.writeValueAsString(arr));
        } catch (Exception e) {
            sb.append("[]");
        }
        sb.append("\n\n");

        sb.append("输出 JSON 结构示例：\n");
        sb.append("{\"header\":\"今天的小建议：...\",\"items\":{\"123\":{\"start\":\"...\",\"done\":\"...\"},\"456\":{\"start\":\"...\",\"done\":\"...\"}}}\n");
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

    private ScheduleAdviceResponse parseScheduleAdvice(String text) {
        try {
            String json = extractJson(text);
            JsonNode root = objectMapper.readTree(json);
            String header = root.hasNonNull("header") ? root.get("header").asText("") : "";
            Map<Long, TaskAdvice> items = new HashMap<>();
            JsonNode itemsNode = root.get("items");
            if (itemsNode != null && itemsNode.isObject()) {
                itemsNode.fields().forEachRemaining(e -> {
                    try {
                        Long taskId = Long.valueOf(e.getKey());
                        JsonNode v = e.getValue();
                        if (v == null || !v.isObject()) return;
                        String start = v.hasNonNull("start") ? v.get("start").asText("") : "";
                        String done = v.hasNonNull("done") ? v.get("done").asText("") : "";
                        items.put(taskId, new TaskAdvice(start, done));
                    } catch (Exception ignored) {
                    }
                });
            }
            return new ScheduleAdviceResponse(header, items);
        } catch (Exception e) {
            return new ScheduleAdviceResponse("", Map.of());
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

    public static class ScheduleAdviceItem {
        public Long taskId;
        public String title;
        public String description;
        public String startTime;
        public String endTime;
        public Integer timeBudgetMinutes;
    }

    public static class TaskAdvice {
        public String start;
        public String done;

        public TaskAdvice() {
        }

        public TaskAdvice(String start, String done) {
            this.start = start;
            this.done = done;
        }
    }

    public static class ScheduleAdviceResponse {
        public String header;
        public Map<Long, TaskAdvice> items;

        public ScheduleAdviceResponse() {
        }

        public ScheduleAdviceResponse(String header, Map<Long, TaskAdvice> items) {
            this.header = header;
            this.items = items;
        }
    }
}
