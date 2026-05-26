package com.chao.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.dto.PunchRecordDto;
import com.chao.common.dto.TaskScheduleDto;
import com.chao.user.dto.SchedulePreferenceDto;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class UserPortraitAiService {
    private final OpenAiCompatClient openAiCompatClient;
    private final ObjectMapper objectMapper;

    public UserPortraitAiService(OpenAiCompatClient openAiCompatClient, ObjectMapper objectMapper) {
        this.openAiCompatClient = openAiCompatClient;
        this.objectMapper = objectMapper;
    }

    public AiPortraitResult analyze(List<PunchRecordDto> records, List<TaskScheduleDto> schedules, int streak) {
        return analyze(records, schedules, streak, List.of());
    }

    public AiPortraitResult analyze(List<PunchRecordDto> records, List<TaskScheduleDto> schedules, int streak,
                                     List<String> journalSnippets) {
        String prompt = buildPrompt(records, schedules, streak, journalSnippets);
        String text = openAiCompatClient.complete(prompt);
        return parse(text);
    }

    private String buildPrompt(List<PunchRecordDto> records, List<TaskScheduleDto> schedules, int streak,
                                 List<String> journalSnippets) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        StringBuilder sb = new StringBuilder();
        sb.append("你是学习习惯分析与学习计划助手。根据用户最近7天的排程与打卡，输出学习画像与建议。\n");
        sb.append("只输出严格 JSON，不要 Markdown，不要额外文字。\n");
        sb.append("JSON 格式如下：\n");
        sb.append("{\"morningPersonScore\":0,\"focusDurationAvg\":45,\"procrastinationIndex\":0.3,");
        sb.append("\"recommendation\":{\"focusMinutes\":45,\"breakMinutes\":10,\"maxDailyMinutes\":240},");
        sb.append("\"tips\":[\"...\"]}\n\n");
        sb.append("约束：\n");
        sb.append("- morningPersonScore: 0-100（越高越偏晨型）\n");
        sb.append("- focusDurationAvg: 15-120（分钟，代表用户实际/适合的单次专注时长）\n");
        sb.append("- procrastinationIndex: 0-1（越高越拖延）\n");
        sb.append("- recommendation.focusMinutes 必须是 30/45/60 之一；breakMinutes 固定 10；maxDailyMinutes 建议 120-300\n");
        sb.append("- tips 给 3-6 条中文短建议。\n\n");

        if (journalSnippets != null && !journalSnippets.isEmpty()) {
            sb.append("用户近期随笔片段（来自向量检索，反映情绪与学习状态）：\n");
            for (String snippet : journalSnippets) {
                sb.append("- ").append(snippet).append("\n");
            }
            sb.append("\n");
        }

        sb.append("连续打卡天数：").append(streak).append("\n");
        sb.append("打卡记录（start-end, createdAt, taskId, durationMinutes）：\n");
        if (records != null) {
            for (PunchRecordDto r : records) {
                if (r == null || r.getCreatedAt() == null) continue;
                Integer sec = r.getDurationSeconds();
                int mins = sec != null && sec > 0 ? Math.max(1, (int) Math.round(sec / 60.0)) : 0;
                if (r.getStartedAt() != null && r.getEndedAt() != null) {
                    sb.append("- ").append(fmt.format(r.getStartedAt())).append(" - ").append(fmt.format(r.getEndedAt())).append(", ");
                } else {
                    sb.append("- -, ");
                }
                sb.append("createdAt=").append(fmt.format(r.getCreatedAt()))
                        .append(", taskId=").append(r.getTaskId())
                        .append(", durationMinutes=").append(mins)
                        .append("\n");
            }
        }
        sb.append("排程记录（startTime-endTime, taskId, status）：\n");
        if (schedules != null) {
            for (TaskScheduleDto s : schedules) {
                if (s == null || s.getStartTime() == null || s.getEndTime() == null) continue;
                sb.append("- ").append(fmt.format(s.getStartTime())).append(" - ").append(fmt.format(s.getEndTime()));
                sb.append(", taskId=").append(s.getTaskId()).append(", status=").append(s.getStatus()).append("\n");
            }
        }
        return sb.toString();
    }

    private AiPortraitResult parse(String text) {
        try {
            String json = extractJson(text);
            JsonNode root = objectMapper.readTree(json);
            AiPortraitResult r = new AiPortraitResult();
            r.morningPersonScore = clampInt(root.path("morningPersonScore").asInt(0), 0, 100);
            r.focusDurationAvg = clampInt(root.path("focusDurationAvg").asInt(45), 15, 120);
            r.procrastinationIndex = clampDouble(root.path("procrastinationIndex").asDouble(0.0), 0.0, 1.0);
            r.tips = objectMapper.convertValue(root.path("tips"), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));

            JsonNode rec = root.path("recommendation");
            SchedulePreferenceDto pref = new SchedulePreferenceDto();
            int focus = rec.path("focusMinutes").asInt(45);
            if (focus != 30 && focus != 45 && focus != 60) {
                focus = 45;
            }
            pref.setFocusMinutes(focus);
            pref.setBreakMinutes(10);
            pref.setMaxDailyMinutes(clampInt(rec.path("maxDailyMinutes").asInt(240), 120, 300));
            r.recommendation = pref;
            return r;
        } catch (Exception e) {
            throw new IllegalStateException("画像解析失败", e);
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

    private int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private double clampDouble(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    @Data
    public static class AiPortraitResult {
        private Integer morningPersonScore;
        private Integer focusDurationAvg;
        private Double procrastinationIndex;
        private SchedulePreferenceDto recommendation;
        private List<String> tips;
    }
}