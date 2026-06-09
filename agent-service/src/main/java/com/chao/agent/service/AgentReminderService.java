package com.chao.agent.service;

import com.chao.common.client.UserInternalClient;
import com.chao.common.config.RabbitMqConfig;
import com.chao.common.dto.NotificationMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AgentReminderService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentReminderService.class);

    private final ReminderDataService dataService;
    private final RedissonClient redissonClient;
    private final RabbitTemplate rabbitTemplate;
    private final UserInternalClient userInternalClient;

    private final ConcurrentHashMap<String, Long> localOncePerDay = new ConcurrentHashMap<>();

    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void cleanupLocalDedup() {
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(36);
        localOncePerDay.entrySet().removeIf(e -> e.getValue() != null && e.getValue() < cutoff);
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 60_000)
    public void tick() {
        Set<Long> userIds = fetchActiveUserIds();
        if (userIds == null || userIds.isEmpty()) return;
        for (Long userId : userIds) {
            if (userId == null || userId <= 0) continue;
            try {
                evaluateAndNotify(userId);
            } catch (Exception e) {
                log.warn("Reminder evaluation failed for userId={}: {}", userId, e.getMessage());
            }
        }
    }

    private Set<Long> fetchActiveUserIds() {
        try {
            List<Long> list = userInternalClient.getActiveUserIds();
            return list != null ? new HashSet<>(list) : Set.of();
        } catch (Exception e) {
            return Set.of();
        }
    }

    private void evaluateAndNotify(Long userId) {
        LocalDateTime now = LocalDateTime.now(ReminderDataService.SHANGHAI);
        LocalDate today = now.toLocalDate();

        var todayData = dataService.loadToday(userId, today);
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
                String delayed = dataService.detectOftenDelayedTaskTitle(userId, now);
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

                String stalled = dataService.detectStalledTaskTitle3Days(userId, today);
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
                if (dataService.weekendOverloaded(userId, today)) {
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
                var jr = dataService.loadJournals(userId, now.minusDays(7));
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
                boolean lowCompletion3Days = dataService.isLowCompletion3Days(userId, today);
                boolean noPunch2Days = dataService.isNoPunch2Days(userId, today);
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

    private String fallback(String trigger, Map<String, Object> data) {
        String t = trigger == null ? "" : trigger.trim();
        return "trigger=" + (t.isBlank() ? "unknown" : t) + "; data=" + String.valueOf(data);
    }

    private static String safeKey(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isBlank()) return "x";
        t = t.replaceAll("\\s+", "_");
        t = t.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]+", "");
        if (t.length() > 36) t = t.substring(0, 36);
        return t.isBlank() ? "x" : t;
    }
}
