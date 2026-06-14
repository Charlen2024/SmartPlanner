package com.chao.user.controller;

import com.chao.common.client.*;
import com.chao.common.dto.*;
import com.chao.user.util.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class UserControllerSupport {

    private final GoalClient goalClient;
    private final PunchClient punchClient;
    private final ResourceClient resourceClient;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    private static final String TASK_RESOURCES_KEY_PREFIX = "sp:task:resources:v2:";

    Long resolveUserId(Jwt jwt, Long headerUserId, Long userId) {
        Long jwtUserId = jwt != null ? JwtUtils.getUserId(jwt) : null;

        if (jwtUserId != null) {
            if (headerUserId != null && !jwtUserId.equals(headerUserId)) {
                throw new IllegalArgumentException("userId mismatch");
            }
            if (userId != null && !jwtUserId.equals(userId)) {
                throw new IllegalArgumentException("userId mismatch");
            }
            return jwtUserId;
        }

        if (headerUserId != null && userId != null && !headerUserId.equals(userId)) {
            throw new IllegalArgumentException("userId mismatch");
        }
        if (headerUserId != null) {
            return headerUserId;
        }
        if (userId != null) {
            return userId;
        }
        throw new IllegalArgumentException("userId required");
    }

    String safeText(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", " ").trim();
    }

    Integer budgetMinutes(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) return null;
        try {
            long m = Duration.between(start, end).toMinutes();
            if (m <= 0) return null;
            return (int) Math.min(480, m);
        } catch (Exception e) {
            return null;
        }
    }

    String fmtHm(DateTimeFormatter hm, LocalDateTime t) {
        if (t == null) return "--:--";
        try {
            return hm.format(t);
        } catch (Exception e) {
            String s = t.toString();
            return s.length() >= 16 ? s.substring(11, 16) : s;
        }
    }

    SchedulePreferenceDto buildSchedulePreference(Long userId) {
        try {
            Result<UserHabitDto> r = punchClient.getHabits(userId);
            UserHabitDto habits = r != null ? r.getData() : null;
            if (habits == null) return null;
            Float pro = habits.getProcrastinationIndex() != null ? habits.getProcrastinationIndex() : 0.3f;
            Integer focusAvg = habits.getFocusDurationAvg() != null ? habits.getFocusDurationAvg() : 45;
            int focus = focusAvg < 40 ? 30 : (focusAvg < 70 ? 45 : 60);
            int maxDaily = 240;
            try {
                Result<Long> sr = punchClient.getStreak(userId);
                Long streak = sr != null ? sr.getData() : null;
                if (streak == null || streak < 3) maxDaily = 180;
            } catch (Exception ignored) {}
            SchedulePreferenceDto pref = new SchedulePreferenceDto();
            pref.setFocusMinutes(focus);
            pref.setBreakMinutes(10);
            pref.setMaxDailyMinutes(maxDaily);
            pref.setProcrastinationIndex(pro);
            return pref;
        } catch (Exception e) {
            return null;
        }
    }

    String buildMoodHint(Long userId) {
        if (userId == null) return "";
        try {
            Result<List<UserJournalDto>> jr = goalClient.listJournals(userId, null);
            List<UserJournalDto> list = jr != null ? jr.getData() : List.of();
            list = list != null ? list : List.of();
            if (list.isEmpty()) return "";

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime from = now.minusDays(7);
            int pos = 0, neg = 0, neu = 0;
            String latestMood = "";
            LocalDateTime latestAt = null;

            for (UserJournalDto j : list) {
                if (j == null) continue;
                String mood = safeText(j.getMood());
                LocalDateTime at = j.getCreatedAt();
                if (!mood.isBlank() && at != null && (latestAt == null || at.isAfter(latestAt))) {
                    latestAt = at;
                    latestMood = mood;
                }
                if (at == null || at.isBefore(from)) continue;
                if (mood.isBlank()) continue;
                int c = moodCategory(mood);
                if (c > 0) pos++;
                else if (c < 0) neg++;
                else neu++;
            }

            if (latestMood.isBlank() && pos == 0 && neg == 0) return "";

            String trend;
            if (neg >= 3 && neg > pos) trend = "最近几天心情偏低或压力偏大";
            else if (pos >= 3 && pos > neg) trend = "最近几天整体状态不错";
            else if (neg >= 2 && pos >= 2) trend = "最近几天状态有波动";
            else trend = "";

            StringBuilder sb = new StringBuilder();
            if (!latestMood.isBlank()) {
                sb.append("最近一次心情：").append(latestMood);
            }
            if (!trend.isBlank()) {
                if (sb.length() > 0) sb.append("；");
                sb.append(trend);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    int moodCategory(String mood) {
        String m = mood == null ? "" : mood;
        if (m.isBlank()) return 0;
        String x = m.toLowerCase();
        if (x.contains("开心") || x.contains("高兴") || x.contains("兴奋") || x.contains("满意")
                || x.contains("充实") || x.contains("自信") || x.contains("轻松")) {
            return 1;
        }
        if (x.contains("焦虑") || x.contains("难过") || x.contains("沮丧") || x.contains("低落")
                || x.contains("崩溃") || x.contains("烦") || x.contains("压力") || x.contains("紧张")
                || x.contains("疲惫") || x.contains("累")) {
            return -1;
        }
        return 0;
    }

    List<CourseResourceDto> recommendCourseResources(Long userId, GoalTaskDto task, int topK) {
        String title = task != null && task.getTitle() != null ? task.getTitle().trim() : "";
        List<CourseResourceDto> out = new ArrayList<>();
        if (title.isBlank()) return out;

        try {
            Result<ResourceAdviceResult> r = resourceClient.searchOnlineCoursesWithAdvice(title);
            ResourceAdviceResult body = r != null ? r.getData() : null;
            List<SearchResourceItem> list = body != null && body.getResources() != null ? body.getResources() : List.of();
            if (list != null) {
                for (SearchResourceItem x : list) {
                    if (x == null) continue;
                    CourseResourceDto dto = new CourseResourceDto();
                    dto.setTopic(title);
                    dto.setTitle(x.getTitle());
                    dto.setPlatform(x.getPlatform());
                    dto.setSourceUrl(x.getUrl());
                    dto.setContentSummary(x.getSummary());
                    out.add(dto);
                    if (out.size() >= topK) break;
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    List<CourseResourceDto> getOrBuildTaskResources(Long userId, Long taskId, GoalTaskDto task, int topK, boolean refresh) {
        if (taskId == null || taskId <= 0) return List.of();
        if (redissonClient == null) return List.of();

        String key = TASK_RESOURCES_KEY_PREFIX + taskId;
        RBucket<String> bucket = redissonClient.getBucket(key);
        if (!refresh) {
            String cached = bucket.get();
            if (cached != null && !cached.isBlank()) {
                try {
                    CourseResourceDto[] arr = objectMapper.readValue(cached, CourseResourceDto[].class);
                    if (arr != null && arr.length > 0) {
                        return List.of(arr);
                    }
                } catch (Exception ignored) {}
            }
        }

        List<CourseResourceDto> rec = recommendCourseResources(userId, task, topK);
        if (rec != null && !rec.isEmpty() && !allAreDefaultFallback(rec)) {
            try {
                String json = objectMapper.writeValueAsString(rec);
                bucket.set(json);
                bucket.expire(Duration.ofDays(2));
            } catch (Exception ignored) {}
        } else {
            try {
                bucket.set("[]");
                bucket.expire(Duration.ofHours(6));
            } catch (Exception ignored) {}
        }
        return rec != null ? rec : List.of();
    }

    boolean allAreDefaultFallback(List<CourseResourceDto> resources) {
        if (resources == null || resources.isEmpty()) return true;
        for (CourseResourceDto r : resources) {
            if (r == null) continue;
            String url = r.getSourceUrl() != null ? r.getSourceUrl() : "";
            if (!isFallbackUrl(url)) return false;
        }
        return true;
    }

    boolean isFallbackUrl(String url) {
        if (url == null || url.isBlank()) return false;
        return url.startsWith("https://www.google.com/search?q=")
            || url.startsWith("https://github.com/search?q=")
            || url.startsWith("https://search.bilibili.com/all?keyword=")
            || url.startsWith("https://www.zhihu.com/search?q=")
            || url.startsWith("https://www.coursera.org/search?query=")
            || url.startsWith("https://www.edx.org/search?q=")
            || url.startsWith("https://medium.com/search?q=");
    }
}
