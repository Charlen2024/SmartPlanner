package com.chao.user.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chao.common.dto.Result;
import lombok.extern.slf4j.Slf4j;
import com.chao.common.client.PunchClient;
import com.chao.common.client.ScheduleClient;
import com.chao.common.dto.PunchRecordDto;
import com.chao.common.dto.TaskScheduleDto;
import com.chao.common.dto.UserHabitDto;
import com.chao.user.dto.UserInsightDto;
import com.chao.user.dto.UserPortraitDto;
import com.chao.user.dto.SchedulePreferenceDto;
import com.chao.user.dto.WeatherDto;
import com.chao.user.service.UserPortraitAiService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class InfoController {
    private final ObjectMapper objectMapper;
    private final PunchClient punchClient;
    private final ScheduleClient scheduleClient;
    private final UserPortraitAiService userPortraitAiService;
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    @GetMapping("/weather")
    public Result<WeatherDto> weather(
            @RequestParam(required = false) String location) {
        String loc = (location != null && !location.isBlank()) ? location.trim() : "Shenzhen";
        WeatherDto dto = new WeatherDto();
        dto.setDate(LocalDate.now().toString());
        dto.setLocation(loc);
        try {
            String encoded = java.net.URLEncoder.encode(loc, java.nio.charset.StandardCharsets.UTF_8);
            String url = "https://wttr.in/" + encoded + "?format=j1";
            HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(java.time.Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode cc = root.path("current_condition");
            if (cc.isArray() && !cc.isEmpty()) {
                JsonNode c = cc.get(0);
                dto.setTemperature(parseDoubleNode(c, "temp_C"));
                dto.setFeelsLike(parseDoubleNode(c, "FeelsLikeC"));
                dto.setWindspeed(parseDoubleNode(c, "windspeedKmph"));
                dto.setHumidity(parseStringNode(c, "humidity"));
                String desc = c.path("weatherDesc").isArray() && !c.path("weatherDesc").isEmpty()
                        ? c.path("weatherDesc").get(0).path("value").asText() : null;
                dto.setSummary(desc != null ? desc : "天气");
            }
        } catch (Exception e) {
            log.warn("Weather fetch failed for location={}: {}", loc, e.toString());
            dto.setSummary("天气服务不可用");
        }
        return Result.success(dto);
    }

    @GetMapping("/insights")
    public Result<UserInsightDto> insights(@AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("userId");
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(7);

        return Result.success(computeInsights(userId, from, to));
    }

    @GetMapping("/portrait")
    public Result<UserPortraitDto> portrait(@AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("userId");
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(7);

        UserPortraitDto dto = new UserPortraitDto();
        dto.setHabits(punchClient.getHabits(userId).getData());
        UserInsightDto insights = computeInsights(userId, from, to);
        dto.setInsights(insights);
        dto.setRecommendation(recommend(insights, dto.getHabits()));
        dto.setTips(insights.getTips());
        return Result.success(dto);
    }

    @PostMapping("/portrait/recompute")
    public Result<UserPortraitDto> recomputePortrait(@AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("userId");
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(7);

        String fStr = from != null ? from.toString() : null;
        String tStr = to != null ? to.toString() : null;
        List<PunchRecordDto> records = punchClient.listRecords(userId, null, fStr, tStr).getData();
        List<TaskScheduleDto> schedules = scheduleClient.listTaskSchedules(userId, fStr, tStr).getData();
        UserInsightDto insights = computeInsights(userId, from, to);
        int streak = insights.getStreak() != null ? insights.getStreak() : 0;

        UserPortraitAiService.AiPortraitResult ai = null;
        try {
        List<String> journalSnippets = searchJournalSnippets(userId);

            ai = userPortraitAiService.analyze(records, schedules, streak, journalSnippets);
        } catch (Exception ignored) {
        }

        int morningScore = computeMorningScore(records, schedules);
        int focusAvg = computeFocusAvgMinutes(records, schedules);
        float proIndex = computeProcrastinationIndex(insights, records, schedules);

        UserHabitDto habits = punchClient.updateHabits(userId, morningScore, focusAvg, proIndex).getData();

        UserPortraitDto dto = new UserPortraitDto();
        dto.setHabits(habits);
        dto.setInsights(insights);
        dto.setRecommendation(ai != null ? ai.getRecommendation() : recommend(insights, habits));
        dto.setTips(insights.getTips());
        return Result.success(dto);
    }

    private List<String> searchJournalSnippets(Long userId) {
        try {
            VectorStore vs = vectorStoreProvider != null ? vectorStoreProvider.getIfAvailable() : null;
            if (vs == null) {
                return List.of();
            }
            FilterExpressionBuilder fb = new FilterExpressionBuilder();
            List<Document> docs = vs.similaritySearch(
                SearchRequest.builder()
                    .query("心情 情绪 学习状态 焦虑 效率 拖延 专注 压力 动力")
                    .topK(8)
                    .filterExpression(fb.eq("userId", userId).build())
                    .build()
            );
            if (docs == null || docs.isEmpty()) {
                return List.of();
            }
            return docs.stream()
                .map(d -> d.getText() != null ? d.getText() : "")
                .filter(s -> !s.isBlank())
                .limit(6)
                .collect(Collectors.toList());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private UserInsightDto computeInsights(Long userId, LocalDateTime from, LocalDateTime to) {
        String fStr = from != null ? from.toString() : null;
        String tStr = to != null ? to.toString() : null;
        List<PunchRecordDto> records = punchClient.listRecords(userId, null, fStr, tStr).getData();
        List<TaskScheduleDto> schedules = scheduleClient.listTaskSchedules(userId, fStr, tStr).getData();

        UserInsightDto dto = new UserInsightDto();
        Long streak = punchClient.getStreak(userId).getData();
        dto.setStreak(streak != null ? streak.intValue() : 0);
        dto.setTips(new ArrayList<>());

        if (records == null || records.isEmpty() || schedules == null || schedules.isEmpty()) {
            dto.setOnTimeRate(0.0);
            dto.setAvgDelayMinutes(0.0);
            dto.setMatchedPunchCount(0);
            dto.setCompletionRate(0.0);
            dto.getTips().add("先完成一次目标拆解与智能排程，系统才能根据行为生成更准确建议");
            dto.getTips().add("建议每天固定一个时间段学习并打卡，连续 7 天后画像会更稳定");
            return dto;
        }

        Map<Long, List<TaskScheduleDto>> scheduleByTask = schedules.stream()
                .filter(s -> s.getTaskId() != null && s.getStartTime() != null)
                .collect(Collectors.groupingBy(TaskScheduleDto::getTaskId));

        int matched = 0;
        int onTime = 0;
        int lateCount = 0;
        long delaySum = 0;

        for (PunchRecordDto r : records) {
            if (r.getTaskId() == null) {
                continue;
            }
            LocalDateTime punchTime = punchStartTime(r);
            if (punchTime == null) {
                continue;
            }
            List<TaskScheduleDto> ss = scheduleByTask.get(r.getTaskId());
            if (ss == null || ss.isEmpty()) {
                continue;
            }
            TaskScheduleDto nearest = ss.stream()
                    .min(Comparator.comparing(s -> Math.abs(ChronoUnit.MINUTES.between(s.getStartTime(), punchTime))))
                    .orElse(null);
            if (nearest == null || nearest.getStartTime() == null) {
                continue;
            }
            long delay = ChronoUnit.MINUTES.between(nearest.getStartTime(), punchTime);
            if (Math.abs(delay) > 180) {
                continue;
            }
            matched++;
            if (delay > 0) {
                delaySum += delay;
                lateCount++;
            }
            if (Math.abs(delay) <= 10) {
                onTime++;
            }
        }

        dto.setOnTimeRate(matched == 0 ? 0.0 : onTime * 1.0 / matched);
        dto.setAvgDelayMinutes(lateCount == 0 ? 0.0 : delaySum * 1.0 / lateCount);
        dto.setMatchedPunchCount(matched);
        long doneSchedules = schedules.stream().filter(s -> s != null && s.getStatus() != null && s.getStatus() == 1).count();
        dto.setCompletionRate(schedules.isEmpty() ? 0.0 : doneSchedules * 1.0 / schedules.size());

        if (matched < 3) {
            dto.getTips().add(String.format("匹配打卡次数 %d（近 7 天），完成率 %.0f%%，准时率 %.0f%%，平均迟到 %.0f 分钟", matched, dto.getCompletionRate() * 100, dto.getOnTimeRate() * 100, dto.getAvgDelayMinutes()));
            dto.getTips().add("匹配数据较少：建议用计时打卡完成 3 次以上后再看画像与建议");
            return dto;
        }

        dto.getTips().add(String.format("完成率 %.0f%%，准时率 %.0f%%，平均迟到 %.0f 分钟（仅统计迟到）", dto.getCompletionRate() * 100, dto.getOnTimeRate() * 100, dto.getAvgDelayMinutes()));
        if (dto.getOnTimeRate() < 0.5) {
            dto.getTips().add("你的打卡更偏“临时起意”，建议把学习安排在固定时间段，提升准时率");
        } else {
            dto.getTips().add("准时率不错，保持固定节奏更容易形成稳定习惯");
        }
        if (dto.getAvgDelayMinutes() > 30 && lateCount >= 2) {
            dto.getTips().add("平均延迟较大，建议把任务拆得更小（30-60 分钟）并减少一次性任务量");
        }
        if (dto.getStreak() < 3) {
            dto.getTips().add("先把连续打卡目标定为 3 天，完成后再提升到 7 天");
        }
        return dto;
    }

    private int computeMorningScore(List<PunchRecordDto> records, List<TaskScheduleDto> schedules) {
        double punchRatio = 0.0;
        if (records != null && !records.isEmpty()) {
            long morning = records.stream().filter(r -> {
                if (r == null) return false;
                LocalDateTime t = punchStartTime(r);
                return t != null && t.getHour() <= 10;
            }).count();
            punchRatio = morning * 1.0 / records.size();
        }
        double scheduleRatio = 0.0;
        if (schedules != null && !schedules.isEmpty()) {
            long morning = schedules.stream().filter(s -> s != null && s.getStartTime() != null && s.getStartTime().getHour() <= 10).count();
            scheduleRatio = morning * 1.0 / schedules.size();
        }
        int score = (int) Math.round((punchRatio * 0.6 + scheduleRatio * 0.4) * 100);
        return Math.max(0, Math.min(100, score));
    }

    private LocalDateTime punchStartTime(PunchRecordDto r) {
        if (r == null) {
            return null;
        }
        if (r.getStartedAt() != null) {
            return r.getStartedAt();
        }
        if (r.getEndedAt() != null && r.getDurationSeconds() != null && r.getDurationSeconds() > 0) {
            return r.getEndedAt().minusSeconds(r.getDurationSeconds());
        }
        if (r.getCreatedAt() != null && r.getDurationSeconds() != null && r.getDurationSeconds() > 0) {
            return r.getCreatedAt().minusSeconds(r.getDurationSeconds());
        }
        return r.getCreatedAt();
    }

    private int computeFocusAvgMinutes(List<PunchRecordDto> records, List<TaskScheduleDto> schedules) {
        if (records != null && !records.isEmpty()) {
            long sum = 0;
            int cnt = 0;
            for (PunchRecordDto r : records) {
                if (r == null || r.getDurationSeconds() == null || r.getDurationSeconds() <= 0) continue;
                sum += Math.max(1, Math.round(r.getDurationSeconds() / 60.0));
                cnt++;
            }
            if (cnt > 0) {
                int avg = (int) Math.round(sum * 1.0 / cnt);
                return Math.max(0, Math.min(180, avg));
            }
        }

        if (schedules == null || schedules.isEmpty()) {
            return 0;
        }
        List<TaskScheduleDto> list = schedules.stream()
                .filter(s -> s != null && s.getStartTime() != null && s.getEndTime() != null)
                .collect(Collectors.toList());
        if (list.isEmpty()) {
            return 0;
        }
        long sum = 0;
        int cnt = 0;
        for (TaskScheduleDto s : list) {
            long mins = ChronoUnit.MINUTES.between(s.getStartTime(), s.getEndTime());
            if (mins <= 0) {
                continue;
            }
            if (s.getStatus() != null && s.getStatus() == 1) {
                sum += mins;
                cnt++;
            }
        }
        if (cnt == 0) {
            for (TaskScheduleDto s : list) {
                long mins = ChronoUnit.MINUTES.between(s.getStartTime(), s.getEndTime());
                if (mins > 0) {
                    sum += mins;
                    cnt++;
                }
            }
        }
        int avg = cnt == 0 ? 0 : (int) Math.round(sum * 1.0 / cnt);
        return Math.max(0, Math.min(180, avg));
    }

    private float computeProcrastinationIndex(UserInsightDto insights, List<PunchRecordDto> records, List<TaskScheduleDto> schedules) {
        if (insights == null) {
            return 0f;
        }
        double delay = insights.getAvgDelayMinutes() != null ? insights.getAvgDelayMinutes() : 0.0;
        double onTime = insights.getOnTimeRate() != null ? insights.getOnTimeRate() : 0.0;
        double delayScore = Math.max(0.0, Math.min(1.0, delay / 180.0));
        double completionRate = 0.0;
        if (schedules != null && !schedules.isEmpty()) {
            long done = schedules.stream().filter(s -> s != null && s.getStatus() != null && s.getStatus() == 1).count();
            completionRate = done * 1.0 / schedules.size();
        }
        Integer matched = insights.getMatchedPunchCount();
        if (matched == null || matched < 3) {
            double pro = 0.3 + (1.0 - completionRate) * 0.7;
            pro = Math.min(0.85, pro);
            return (float) Math.max(0.0, Math.min(1.0, pro));
        }
        double pro = delayScore * 0.45 + (1.0 - onTime) * 0.35 + (1.0 - completionRate) * 0.20;
        return (float) Math.max(0.0, Math.min(1.0, pro));
    }

    private SchedulePreferenceDto recommend(UserInsightDto insights, UserHabitDto habits) {
        int focusAvg = habits != null && habits.getFocusDurationAvg() != null ? habits.getFocusDurationAvg() : 45;
        int focus;
        if (focusAvg < 40) {
            focus = 30;
        } else if (focusAvg < 70) {
            focus = 45;
        } else {
            focus = 60;
        }
        int maxDaily = 240;
        if (insights != null) {
            int streak = insights.getStreak() != null ? insights.getStreak() : 0;
            double onTime = insights.getOnTimeRate() != null ? insights.getOnTimeRate() : 0.0;
            if (streak < 3 || onTime < 0.5) {
                maxDaily = 180;
            }
        }
        SchedulePreferenceDto dto = new SchedulePreferenceDto();
        dto.setFocusMinutes(focus);
        dto.setBreakMinutes(10);
        dto.setMaxDailyMinutes(maxDaily);
        return dto;
    }

    private Double parseDoubleNode(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (n.isNull() || n.isMissingNode()) return null;
        if (n.isNumber()) return n.asDouble();
        if (n.isTextual()) {
            try { return Double.parseDouble(n.asText().trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private String parseStringNode(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (n.isNull() || n.isMissingNode()) return null;
        if (n.isTextual()) return n.asText().trim();
        return n.asText();
    }

    private String weatherSummary(Integer code) {
        if (code == null) {
            return "未知";
        }
        if (code == 0) return "晴";
        if (code == 1 || code == 2 || code == 3) return "多云";
        if (code == 45 || code == 48) return "雾";
        if (code == 51 || code == 53 || code == 55) return "毛毛雨";
        if (code == 61 || code == 63 || code == 65) return "下雨";
        if (code == 71 || code == 73 || code == 75) return "下雪";
        if (code == 80 || code == 81 || code == 82) return "阵雨";
        if (code == 95 || code == 96 || code == 99) return "雷暴";
        return "天气";
    }
}
