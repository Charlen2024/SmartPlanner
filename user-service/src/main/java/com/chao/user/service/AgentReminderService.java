package com.chao.user.service;

import com.chao.common.client.GoalClient;
import com.chao.common.client.PunchClient;
import com.chao.common.client.ScheduleClient;
import com.chao.common.config.RabbitMqConfig;
import com.chao.common.dto.NotificationMessage;
import com.chao.common.dto.PunchRecordDto;
import com.chao.common.dto.TaskScheduleDto;
import com.chao.common.dto.UserJournalDto;
import com.chao.user.controller.NotificationController;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AgentReminderService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final NotificationController notificationController;
    private final ScheduleClient scheduleClient;
    private final PunchClient punchClient;
    private final GoalClient goalClient;
    private final RedissonClient redissonClient;
    private final RabbitTemplate rabbitTemplate;

    private final ConcurrentHashMap<String, Long> localOncePerDay = new ConcurrentHashMap<>();

    @Scheduled(initialDelay = 15_000, fixedDelay = 60_000)
    public void tick() {
        Set<Long> userIds = notificationController.activeUserIds();
        if (userIds == null || userIds.isEmpty()) return;
        for (Long userId : userIds) {
            if (userId == null || userId <= 0) continue;
            try {
                evaluateAndNotify(userId);
            } catch (Exception ignored) {
            }
        }
    }

    private void evaluateAndNotify(Long userId) {
        LocalDateTime now = LocalDateTime.now(SHANGHAI);
        LocalDate today = now.toLocalDate();

        var todayData = loadToday(userId, today);
        int pendingCount = todayData.pendingTitles.size();

        sendOncePerDay(userId, "daily_summary", today, () -> {
            Map<String, Object> aiData = Map.of(
                    "totalPlanCount", todayData.totalPlanCount,
                    "pendingCount", pendingCount,
                    "streak", todayData.streak
            );
            String fallback = fallback("daily_summary", aiData);
            reminder(userId, "AGENT_REMINDER", fallback, Map.of("nav", "/punch", "level", "info",
                    "ai", Map.of(
                            "userPrompt", "触发：daily_summary。请基于数据生成 1-2 句中文关怀提醒，语气温和，可执行，避免固定模板与重复句式。数据：" + aiData
                    ),
                    "data", Map.of(
                            "completed", todayData.completedTitles,
                            "pending", todayData.pendingTitles,
                            "streak", todayData.streak,
                            "completionHours", todayData.completionHours
                    )));
        });

        if (now.getHour() == 21 && now.getMinute() <= 5) {
            if (pendingCount >= 2) {
                String a = todayData.pendingTitles.get(0);
                String b = todayData.pendingTitles.get(1);
                sendOncePerDay(userId, "punch_21_pending2", today, () ->
                        reminder(userId, "AGENT_REMINDER",
                                fallback("punch_pending_21", Map.of("pendingTop2", List.of(a, b), "pendingCount", pendingCount)),
                                Map.of("nav", "/punch", "level", "warning",
                                        "ai", Map.of(
                                                "userPrompt", "触发：punch_pending_21。请基于数据生成 1-2 句中文关怀提醒，避免固定模板与重复句式，给一个最小动作建议。数据：" + Map.of(
                                                        "pendingTop2", List.of(a, b),
                                                        "pendingCount", pendingCount,
                                                        "streak", todayData.streak
                                                )
                                        ),
                                        "data", Map.of(
                                                "completed", todayData.completedTitles,
                                                "pending", todayData.pendingTitles,
                                                "streak", todayData.streak,
                                                "completionHours", todayData.completionHours
                                        ))));
            } else if (todayData.streak >= 7 && pendingCount == 0 && todayData.totalPlanCount > 0) {
                sendOncePerDay(userId, "badge_streak7_all", today, () ->
                        reminder(userId, "AGENT_BADGE",
                                fallback("badge_streak7_all", Map.of("streak", todayData.streak, "badge", "self_discipline_star")),
                                Map.of("nav", "/punch", "level", "success",
                                        "badge", "self_discipline_star",
                                        "ai", Map.of(
                                                "userPrompt", "触发：badge_streak7_all。请基于数据生成一句成就提醒（不固定模板），语气真诚但不过度夸张。数据：" + Map.of(
                                                        "streak", todayData.streak,
                                                        "badge", "self_discipline_star"
                                                )
                                        ),
                                        "data", Map.of("streak", todayData.streak))));
            }
        }

        if (now.getMinute() <= 5) {
            if (now.getHour() == 12 || now.getHour() == 18) {
                String delayed = detectOftenDelayedTaskTitle(userId, now);
                if (!delayed.isBlank()) {
                    sendOncePerDay(userId, "task_delay3_" + safeKey(delayed), today, () ->
                            reminder(userId, "AGENT_REMINDER",
                                    fallback("task_delay_3times", Map.of("taskTitle", delayed)),
                                    Map.of("nav", "/goals", "level", "info",
                                            "ai", Map.of(
                                                    "userPrompt", "触发：task_delay_3times。任务多次推迟。请基于数据生成 1-2 句中文关怀提醒，避免固定模板与重复句式，提出一个可执行的小建议。数据：" + Map.of(
                                                            "taskTitle", delayed
                                                    )
                                            ),
                                            "data", Map.of("taskTitle", delayed))));
                }

                String stalled = detectStalledTaskTitle3Days(userId, today);
                if (!stalled.isBlank()) {
                    sendOncePerDay(userId, "task_stalled3_" + safeKey(stalled), today, () ->
                            reminder(userId, "AGENT_REMINDER",
                                    fallback("task_stalled_3days", Map.of("taskTitle", stalled)),
                                    Map.of("nav", "/goals", "level", "warning",
                                            "ai", Map.of(
                                                    "userPrompt", "触发：task_stalled_3days。请基于数据生成 1-2 句中文关怀提醒，避免固定模板与重复句式，提出一个可执行的小建议。数据：" + Map.of(
                                                            "taskTitle", stalled
                                                    )
                                            ),
                                            "data", Map.of("taskTitle", stalled))));
                }
            }

            if (now.getDayOfWeek() == DayOfWeek.FRIDAY && now.getHour() == 18) {
                if (weekendOverloaded(userId, today)) {
                    sendOncePerDay(userId, "weekend_rest_hint", today, () ->
                            reminder(userId, "AGENT_REMINDER",
                                    fallback("weekend_rest_hint", Map.of("week", "overloaded")),
                                    Map.of("nav", "/schedule", "level", "info",
                                            "ai", Map.of(
                                                    "userPrompt", "触发：weekend_rest_hint。请生成 1-2 句周末休息/节奏调整的关怀提醒，避免固定模板与重复句式。数据：" + Map.of(
                                                            "week", "overloaded"
                                                    )
                                            ))));
                }
            }

            if (now.getHour() == 20) {
                var jr = loadJournals(userId, now.minusDays(7));
                if (jr.noJournalDays >= 3) {
                    sendOncePerDay(userId, "journal_none3", today, () ->
                            reminder(userId, "AGENT_REMINDER",
                                    fallback("journal_none_3days", Map.of("noJournalDays", jr.noJournalDays)),
                                    Map.of("nav", "/journals", "level", "info",
                                            "ai", Map.of(
                                                    "userPrompt", "触发：journal_none_3days。请生成 1-2 句温和提醒，引导用户记录近况/心情，避免固定模板。数据：" + Map.of(
                                                            "noJournalDays", jr.noJournalDays
                                                    )
                                            ),
                                            "data", Map.of("noJournalDays", jr.noJournalDays))));
                }
                if (jr.hasStressWords) {
                    sendOncePerDay(userId, "journal_stress", today, () ->
                            reminder(userId, "AGENT_REMINDER",
                                    fallback("journal_stress_words", Map.of("hasStressWords", true)),
                                    Map.of("nav", "/journals", "level", "warning",
                                            "ai", Map.of(
                                                    "userPrompt", "触发：journal_stress_words。请生成 1-2 句安慰提醒，避免固定模板与重复句式，给一个可执行的小建议。数据：" + Map.of(
                                                            "hasStressWords", true
                                                    )
                                            ))));
                }
                if (jr.shortJournal7Days) {
                    sendOncePerDay(userId, "journal_short7", today, () ->
                            reminder(userId, "AGENT_REMINDER",
                                    fallback("journal_short_7days", Map.of("shortJournal7Days", true)),
                                    Map.of("nav", "/journals", "level", "info",
                                            "ai", Map.of(
                                                    "userPrompt", "触发：journal_short_7days。请生成 1-2 句温和引导，鼓励用户写得更具体一点或关注一件小事，避免固定模板。数据：" + Map.of(
                                                            "shortJournal7Days", true
                                                    )
                                            ))));
                }
            }

            if (now.getHour() == 12 || now.getHour() == 18 || now.getHour() == 21) {
                boolean lowCompletion3Days = isLowCompletion3Days(userId, today);
                boolean noPunch2Days = isNoPunch2Days(userId, today);
                if (lowCompletion3Days || noPunch2Days) {
                    String reason = lowCompletion3Days ? "连续 3 天任务完成率低" : "连续 2 天没有打卡";
                    sendOncePerDay(userId, "low_engagement_" + safeKey(reason), today, () ->
                            reminder(userId, "AGENT_REMINDER",
                                    fallback("low_engagement", Map.of(
                                            "reason", reason,
                                            "completionRateLow3Days", lowCompletion3Days,
                                            "noPunch2Days", noPunch2Days
                                    )),
                                    Map.of("nav", "/goals", "level", "warning",
                                            "ai", Map.of(
                                                    "userPrompt", "触发：low_engagement。请基于数据生成 1-2 句中文关怀提醒，避免固定模板与重复句式，询问是否需要调整/拆分任务，并给一个最小动作建议。数据：" + Map.of(
                                                            "reason", reason,
                                                            "completionRateLow3Days", lowCompletion3Days,
                                                            "noPunch2Days", noPunch2Days
                                                    )
                                            ),
                                            "data", Map.of(
                                                    "reason", reason,
                                                    "completionRateLow3Days", lowCompletion3Days,
                                                    "noPunch2Days", noPunch2Days
                                            ))));
                }
            }
        }
    }

    private String fallback(String trigger, Map<String, Object> data) {
        String t = trigger == null ? "" : trigger.trim();
        return "trigger=" + (t.isBlank() ? "unknown" : t) + "; data=" + String.valueOf(data);
    }

    private boolean isLowCompletion3Days(Long userId, LocalDate today) {
        LocalDate d0 = today;
        LocalDate d1 = today.minusDays(1);
        LocalDate d2 = today.minusDays(2);

        LocalDateTime from = d2.atStartOfDay();
        LocalDateTime to = d0.atTime(23, 59, 59);
        List<TaskScheduleDto> schedules = safe(scheduleClient.listTaskSchedules(userId, from.toString(), to.toString()).getData());

        Map<LocalDate, int[]> map = new HashMap<>();
        for (TaskScheduleDto s : schedules) {
            if (s == null || s.getStartTime() == null) continue;
            LocalDate day = s.getStartTime().toLocalDate();
            if (!(day.equals(d0) || day.equals(d1) || day.equals(d2))) continue;
            int[] arr = map.computeIfAbsent(day, k -> new int[]{0, 0});
            arr[0] += 1;
            if (s.getStatus() != null && s.getStatus() == 1) arr[1] += 1;
        }

        if (!map.containsKey(d0) || !map.containsKey(d1) || !map.containsKey(d2)) return false;
        for (LocalDate d : List.of(d0, d1, d2)) {
            int[] arr = map.get(d);
            if (arr == null || arr[0] <= 0) return false;
            double rate = arr[1] * 1.0d / Math.max(1, arr[0]);
            if (rate >= 0.3d) return false;
        }
        return true;
    }

    private boolean isNoPunch2Days(Long userId, LocalDate today) {
        LocalDate d0 = today;
        LocalDate d1 = today.minusDays(1);
        LocalDateTime from = d1.atStartOfDay();
        LocalDateTime to = d0.atTime(23, 59, 59);
        List<PunchRecordDto> records = safe(punchClient.listRecords(userId, null, from.toString(), to.toString()).getData());
        boolean has0 = false;
        boolean has1 = false;
        for (PunchRecordDto r : records) {
            if (r == null || r.getCreatedAt() == null) continue;
            LocalDate d = r.getCreatedAt().toLocalDate();
            if (d.equals(d0)) has0 = true;
            if (d.equals(d1)) has1 = true;
        }
        return !has0 && !has1;
    }

    private TodayData loadToday(Long userId, LocalDate date) {
        String from = date + "T00:00:00";
        String to = date + "T23:59:59";
        List<TaskScheduleDto> schedules = safe(scheduleClient.listTaskSchedules(userId, from, to).getData());
        List<PunchRecordDto> records = safe(punchClient.listRecords(userId, null, from, to).getData());
        long streak = safeLong(punchClient.getStreak(userId).getData());

        Set<Long> punchedTaskIds = new HashSet<>();
        List<Integer> completionHours = new ArrayList<>();
        for (PunchRecordDto r : records) {
            if (r == null) continue;
            if (r.getTaskId() != null) punchedTaskIds.add(r.getTaskId());
            if (r.getCreatedAt() != null) completionHours.add(r.getCreatedAt().getHour());
        }

        List<String> completedTitles = new ArrayList<>();
        List<String> pendingTitles = new ArrayList<>();

        for (TaskScheduleDto s : schedules) {
            if (s == null) continue;
            Long tid = s.getTaskId();
            boolean done = (s.getStatus() != null && s.getStatus() == 1) || (tid != null && punchedTaskIds.contains(tid));
            String title = s.getTaskTitle() != null && !s.getTaskTitle().isBlank() ? s.getTaskTitle().trim() : ("任务 " + (tid != null ? tid : ""));
            if (done) completedTitles.add(title);
            else pendingTitles.add(title);
        }

        completedTitles.sort(String::compareTo);
        pendingTitles.sort(String::compareTo);
        completionHours.sort(Comparator.naturalOrder());

        TodayData out = new TodayData();
        out.completedTitles = completedTitles;
        out.pendingTitles = pendingTitles;
        out.streak = streak;
        out.totalPlanCount = schedules.size();
        out.completionHours = completionHours;
        return out;
    }

    private String detectOftenDelayedTaskTitle(Long userId, LocalDateTime now) {
        LocalDateTime from = now.minusDays(7).toLocalDate().atStartOfDay();
        LocalDateTime to = now.toLocalDate().atTime(23, 59, 59);
        List<TaskScheduleDto> schedules = safe(scheduleClient.listTaskSchedules(userId, from.toString(), to.toString()).getData());

        Map<String, Integer> lateCounts = new HashMap<>();
        for (TaskScheduleDto s : schedules) {
            if (s == null) continue;
            String title = s.getTaskTitle() != null ? s.getTaskTitle().trim() : "";
            if (title.isBlank()) continue;
            LocalDateTime end = s.getEndTime();
            if (end == null) continue;
            if (end.isAfter(now)) continue;
            if (s.getStatus() != null && s.getStatus() == 1) continue;
            lateCounts.put(title, (lateCounts.get(title) == null ? 0 : lateCounts.get(title)) + 1);
        }
        return lateCounts.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() >= 3)
                .max(Comparator.comparingInt(e -> e.getValue() != null ? e.getValue() : 0))
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private String detectStalledTaskTitle3Days(Long userId, LocalDate today) {
        LocalDate d1 = today.minusDays(1);
        LocalDate d2 = today.minusDays(2);

        LocalDateTime from = d2.atStartOfDay();
        LocalDateTime to = today.atTime(23, 59, 59);
        List<TaskScheduleDto> schedules = safe(scheduleClient.listTaskSchedules(userId, from.toString(), to.toString()).getData());

        Map<String, Map<LocalDate, int[]>> stats = new HashMap<>();
        for (TaskScheduleDto s : schedules) {
            if (s == null) continue;
            String title = s.getTaskTitle() != null ? s.getTaskTitle().trim() : "";
            if (title.isBlank()) continue;
            LocalDateTime st = s.getStartTime();
            if (st == null) continue;
            LocalDate day = st.toLocalDate();
            if (!(day.equals(today) || day.equals(d1) || day.equals(d2))) continue;

            Map<LocalDate, int[]> byDay = stats.computeIfAbsent(title, k -> new HashMap<>());
            int[] arr = byDay.computeIfAbsent(day, k -> new int[]{0, 0});
            arr[0] += 1;
            if (s.getStatus() != null && s.getStatus() == 1) arr[1] += 1;
        }

        for (var e : stats.entrySet()) {
            String title = e.getKey();
            Map<LocalDate, int[]> byDay = e.getValue();
            if (byDay == null) continue;
            if (!byDay.containsKey(today) || !byDay.containsKey(d1) || !byDay.containsKey(d2)) continue;
            if (byDay.get(today)[0] == 0 || byDay.get(d1)[0] == 0 || byDay.get(d2)[0] == 0) continue;
            if (byDay.get(today)[1] != 0) continue;
            if (byDay.get(d1)[1] != 0) continue;
            if (byDay.get(d2)[1] != 0) continue;
            return title;
        }

        return "";
    }

    private boolean weekendOverloaded(Long userId, LocalDate today) {
        LocalDate sat = today.with(DayOfWeek.SATURDAY);
        if (!sat.isAfter(today)) sat = sat.plusWeeks(1);
        LocalDate sun = sat.plusDays(1);

        LocalDateTime from = sat.atStartOfDay();
        LocalDateTime to = sun.atTime(23, 59, 59);

        List<TaskScheduleDto> schedules = safe(scheduleClient.listTaskSchedules(userId, from.toString(), to.toString()).getData());
        long minutes = 0;
        for (TaskScheduleDto s : schedules) {
            if (s == null || s.getStartTime() == null || s.getEndTime() == null) continue;
            long m = Duration.between(s.getStartTime(), s.getEndTime()).toMinutes();
            if (m > 0 && m < 24 * 60) minutes += m;
        }
        return minutes >= 8 * 60;
    }

    private JournalData loadJournals(Long userId, LocalDateTime since) {
        List<UserJournalDto> all = safe(goalClient.listJournals(userId, null).getData());
        List<UserJournalDto> list = new ArrayList<>();
        for (UserJournalDto j : all) {
            if (j == null || j.getCreatedAt() == null) continue;
            if (j.getCreatedAt().isBefore(since)) continue;
            list.add(j);
        }
        list.sort(Comparator.comparing(UserJournalDto::getCreatedAt));

        JournalData out = new JournalData();
        out.noJournalDays = computeNoJournalDays(list);
        out.hasStressWords = containsStressWords(list);
        out.shortJournal7Days = isShortJournal7Days(list);
        return out;
    }

    private int computeNoJournalDays(List<UserJournalDto> list) {
        if (list == null || list.isEmpty()) return 999;
        LocalDate latest = list.get(list.size() - 1).getCreatedAt().toLocalDate();
        LocalDate today = LocalDate.now(SHANGHAI);
        long days = Duration.between(latest.atStartOfDay(), today.atStartOfDay()).toDays();
        return (int) Math.max(0, days);
    }

    private boolean containsStressWords(List<UserJournalDto> list) {
        if (list == null || list.isEmpty()) return false;
        Set<String> words = Set.of("焦虑", "压力", "崩溃", "很累", "抑郁", "烦", "难受");
        for (int i = Math.max(0, list.size() - 6); i < list.size(); i++) {
            UserJournalDto j = list.get(i);
            if (j == null) continue;
            String c = j.getContent() != null ? j.getContent() : "";
            for (String w : words) {
                if (!w.isBlank() && c.contains(w)) return true;
            }
            String mood = j.getMood() != null ? j.getMood() : "";
            for (String w : words) {
                if (!w.isBlank() && mood.contains(w)) return true;
            }
        }
        return false;
    }

    private boolean isShortJournal7Days(List<UserJournalDto> list) {
        if (list == null || list.isEmpty()) return false;
        LocalDate today = LocalDate.now(SHANGHAI);
        Map<LocalDate, Integer> maxLen = new HashMap<>();
        for (UserJournalDto j : list) {
            if (j == null || j.getCreatedAt() == null) continue;
            LocalDate d = j.getCreatedAt().toLocalDate();
            if (d.isBefore(today.minusDays(6))) continue;
            int len = j.getContent() != null ? j.getContent().trim().length() : 0;
            maxLen.put(d, Math.max(maxLen.getOrDefault(d, 0), len));
        }
        if (maxLen.size() < 7) return false;
        for (int i = 0; i < 7; i++) {
            LocalDate d = today.minusDays(i);
            int l = maxLen.getOrDefault(d, 0);
            if (l >= 60) return false;
        }
        return true;
    }

    private void sendOncePerDay(Long userId, String rule, LocalDate date, Runnable send) {
        String key = "sp:agent:reminder:" + userId + ":" + rule + ":" + date;
        boolean ok;
        try {
            ok = redissonClient.getBucket(key).trySet("1", 30, TimeUnit.HOURS);
        } catch (Exception e) {
            ok = trySetLocalOnce(key, 30 * 60 * 60 * 1000L);
        }
        if (ok) send.run();
    }

    private boolean trySetLocalOnce(String key, long ttlMs) {
        if (key == null || key.isBlank()) return true;
        long now = System.currentTimeMillis();
        Long prev = localOncePerDay.putIfAbsent(key, now);
        if (prev == null) return true;

        if (ttlMs <= 0) return false;
        if (now - prev >= ttlMs) {
            localOncePerDay.put(key, now);
            return true;
        }
        return false;
    }

    private void reminder(Long userId, String type, String content, Map<String, Object> payload) {
        NotificationMessage m = new NotificationMessage();
        m.setUserId(userId);
        m.setType(type);
        m.setContent(content);
        m.setTs(System.currentTimeMillis());
        m.setPayload(payload);
        rabbitTemplate.convertAndSend(RabbitMqConfig.NOTIFICATION_EXCHANGE, RabbitMqConfig.NOTIFICATION_ROUTING_KEY, m);
    }

    private static String safeKey(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isBlank()) return "x";
        t = t.replaceAll("\\s+", "_");
        t = t.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]+", "");
        if (t.length() > 18) t = t.substring(0, 18);
        return t.isBlank() ? "x" : t;
    }

    private static <T> List<T> safe(List<T> list) {
        return list != null ? list : List.of();
    }

    private static long safeLong(Long v) {
        return v != null ? v : 0L;
    }

    private static class TodayData {
        List<String> completedTitles = List.of();
        List<String> pendingTitles = List.of();
        long streak;
        int totalPlanCount;
        List<Integer> completionHours = List.of();
    }

    private static class JournalData {
        int noJournalDays;
        boolean hasStressWords;
        boolean shortJournal7Days;
    }
}
