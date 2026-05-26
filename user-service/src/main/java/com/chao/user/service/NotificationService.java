package com.chao.user.service;

import com.chao.common.ai.OpenAiCompatClient;
import com.chao.common.config.RabbitMqConfig;
import com.chao.common.dto.NotificationMessage;
import com.chao.user.controller.NotificationController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationController notificationController;
    private final OpenAiCompatClient openAiCompatClient;
    private final RedissonClient redissonClient;

    private final ConcurrentHashMap<Long, String> lastLoginCareByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> lastLoginCareTsByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> localDuplicateGuard = new ConcurrentHashMap<>();

    private static final long DUP_GUARD_TTL_MS = 2 * 60_000L;

    private static final String REMINDER_SYSTEM_PROMPT = """
            你是 SmartPlanner 的学习关怀提醒助手。
            你会根据触发原因与结构化数据，为用户生成一条中文提醒/鼓励/安慰。

            输出要求：
            1) 只输出提醒正文，不要 Markdown，不要任何格式符号，不要表情符号。
            2) 1-2 句，尽量 45-80 字。
            3) 语气温和、可执行，避免指责，避免夸张承诺。
            """;

    @RabbitListener(queues = RabbitMqConfig.NOTIFICATION_QUEUE)
    public void receiveNotification(NotificationMessage message) {
        tryAiRewrite(message);
        postProcess(message);
        String safeText = sanitize(String.valueOf(message != null ? message.getContent() : ""));
        if (safeText.isBlank()) {
            return;
        }
        if (shouldDropDuplicate(message)) {
            return;
        }
        log.info("MQ收到通知，推给用户 {}: {}", message.getUserId(), message.getContent());
        notificationController.pushNotification(message);
    }

    private void tryAiRewrite(NotificationMessage message) {
        if (message == null) return;
        if (openAiCompatClient == null) return;
        Object payload = message.getPayload();
        if (!(payload instanceof java.util.Map<?, ?> p)) return;
        Object aiObj = p.get("ai");
        if (!(aiObj instanceof java.util.Map<?, ?> ai)) return;

        String userPrompt = ai.get("userPrompt") != null ? String.valueOf(ai.get("userPrompt")) : "";
        if (userPrompt.isBlank()) return;
        String systemPrompt = ai.get("systemPrompt") != null ? String.valueOf(ai.get("systemPrompt")) : REMINDER_SYSTEM_PROMPT;
        String trigger = getTrigger(p);

        String out = "";
        try {
            out = openAiCompatClient.complete(systemPrompt, userPrompt);
        } catch (Exception ignored) {
            try {
                out = openAiCompatClient.complete(systemPrompt, userPrompt);
            } catch (Exception ignored2) {
                if (isLoginCare(trigger)) {
                    message.setContent("");
                }
                return;
            }
        }
        try {
            String cleaned = sanitize(out);
            String finalText = validateOrFallback(cleaned, userPrompt, String.valueOf(message.getContent()), trigger);
            if (!finalText.isBlank()) {
                message.setContent(finalText);
            } else if (isLoginCare(trigger)) {
                message.setContent("");
            }
        } catch (Exception ignored) {
        }
    }

    private String validateOrFallback(String aiText, String userPrompt, String fallback, String trigger) {
        String t = sanitize(aiText);
        if (t.isBlank()) return sanitize(fallback);

        ParsedLoginCare p = parseLoginCare(userPrompt);
        boolean hasPersonal = false;
        if (p.nextTask != null && !p.nextTask.isBlank() && !p.nextTask.equals("无") && t.contains(p.nextTask)) hasPersonal = true;
        if (!hasPersonal && p.streak > 0 && t.contains(String.valueOf(p.streak))) hasPersonal = true;
        if (!hasPersonal && p.pending != null && t.contains(String.valueOf(p.pending))) hasPersonal = true;
        if (!hasPersonal && p.mood != null && !p.mood.isBlank() && !p.mood.equals("无") && t.contains(p.mood)) hasPersonal = true;

        boolean contradictsCounts = false;
        if (p.total != null && p.pending != null && p.total > 0 && p.pending >= 0 && p.pending <= p.total) {
            int done = p.total - p.pending;
            if (done == 0) {
                boolean saysDone = t.contains("已完成") || t.contains("完成了") || t.contains("完成不少") || t.contains("完成很多");
                if (saysDone && !t.contains("未完成")) contradictsCounts = true;
            }
            Integer claimedDone = extractDoneCount(t);
            if (claimedDone != null && claimedDone > done + 1) contradictsCounts = true;
        }

        boolean nextIsVocabulary = p.nextTask != null && (p.nextTask.contains("词汇") || p.nextTask.contains("背单词") || p.nextTask.contains("英语"));
        boolean looksGeneric = (t.contains("词汇积累") || t.contains("背单词")) && !nextIsVocabulary;
        looksGeneric = looksGeneric || t.contains("继续努力") || t.contains("一步步来");
        boolean isLoginCare = isLoginCare(trigger);
        boolean tooShort = isLoginCare && t.length() < 18;
        if (contradictsCounts || tooShort) {
            if (isLoginCare) return "";
            return sanitize(fallback);
        }
        return t;
    }

    private void postProcess(NotificationMessage message) {
        if (message == null) return;
        Long userId = message.getUserId();
        if (userId == null) return;
        Object payloadObj = message.getPayload();
        if (!(payloadObj instanceof Map<?, ?> payload)) return;

        String trigger = getTrigger(payload);
        if (!isLoginCare(trigger)) return;

        long now = System.currentTimeMillis();
        String cur = sanitize(String.valueOf(message.getContent()));
        if (cur.isBlank()) return;

        Long ts = lastLoginCareTsByUser.get(userId);
        String prev = lastLoginCareByUser.get(userId);
        boolean inWindow = ts != null && now - ts <= 5 * 60_000L;

        if (inWindow && prev != null && !prev.isBlank() && isSimilar(cur, prev)) {
            String regenerated = sanitize(regenerateDifferentFromPrev(payload, prev));
            if (!regenerated.isBlank() && !isSimilar(regenerated, prev)) {
                message.setContent(regenerated);
                cur = regenerated;
            }
            if ("login_care_follow".equals(trigger) && isSimilar(cur, prev)) {
                message.setContent("");
                return;
            }
        }

        lastLoginCareByUser.put(userId, cur);
        lastLoginCareTsByUser.put(userId, now);
    }

    private String regenerateDifferentFromPrev(Map<?, ?> payload, String prev) {
        if (openAiCompatClient == null) return "";
        if (payload == null) return "";
        Object aiObj = payload.get("ai");
        if (!(aiObj instanceof Map<?, ?> ai)) return "";
        Object up = ai.get("userPrompt");
        if (up == null) return "";
        String userPrompt = String.valueOf(up);
        if (userPrompt.isBlank()) return "";

        String extra = "\n\n上一条提醒正文：" + prev
                + "\n要求：这次要换一个完全不同的表达方式与角度，避免同义改写。"
                + "\n避免重复出现：今天还有/未完成/先选1项/先挑1项/10分钟（可换成更小的动作或更不同的动作，如喝水、伸展、整理桌面、打开日程）。"
                + "\n仍需引用数据中的至少一个具体信息。";
        try {
            return openAiCompatClient.complete(REMINDER_SYSTEM_PROMPT, userPrompt + extra);
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean shouldDropDuplicate(NotificationMessage message) {
        if (message == null) return false;
        Long userId = message.getUserId();
        if (userId == null || userId <= 0) return false;
        String type = message.getType() != null ? message.getType().trim() : "";
        if (type.isBlank()) return false;
        if (!"AGENT_REMINDER".equals(type) && !"AGENT_BADGE".equals(type)) return false;

        String content = sanitize(String.valueOf(message.getContent()));
        if (content.isBlank()) return false;

        String key = null;
        Object payloadObj = message.getPayload();
        if (payloadObj instanceof Map<?, ?> payload) {
            Object dataObj = payload.get("data");
            if (dataObj instanceof Map<?, ?> data) {
                Object dk = data.get("dedupKey");
                if (dk != null && !String.valueOf(dk).isBlank()) {
                    key = "sp:notif:dedup:" + userId + ":" + String.valueOf(dk).trim();
                }
            }
        }
        if (key == null) {
            key = "sp:notif:dedup:" + userId + ":" + type + ":" + Integer.toHexString(content.hashCode());
        }
        boolean ok = false;
        try {
            if (redissonClient != null) {
                ok = redissonClient.getBucket(key).trySet("1", DUP_GUARD_TTL_MS, TimeUnit.MILLISECONDS);
            }
        } catch (Exception ignored) {
        }
        if (ok) return false;

        long now = System.currentTimeMillis();
        Long prev = localDuplicateGuard.putIfAbsent(key, now);
        if (prev == null) return false;
        if (now - prev >= DUP_GUARD_TTL_MS) {
            localDuplicateGuard.put(key, now);
            return false;
        }
        return true;
    }

    private boolean isLoginCare(String trigger) {
        return "login_care".equals(trigger) || "login_care_follow".equals(trigger);
    }

    private boolean hasCareTone(String t) {
        if (t == null) return false;
        return containsAny(t, "别急", "慢慢", "没关系", "照顾", "休息", "喝口水", "深呼吸", "允许", "别把自己", "不必");
    }

    private boolean containsAny(String s, String... needles) {
        if (s == null || needles == null) return false;
        for (String n : needles) {
            if (n == null || n.isBlank()) continue;
            if (s.contains(n)) return true;
        }
        return false;
    }

    private boolean isSimilar(String a, String b) {
        String x = normalizeForSimilarity(a);
        String y = normalizeForSimilarity(b);
        if (x.isBlank() || y.isBlank()) return false;
        if (x.equals(y)) return true;
        if (x.length() < 8 || y.length() < 8) return false;

        if (x.contains(y) || y.contains(x)) {
            int min = Math.min(x.length(), y.length());
            int max = Math.max(x.length(), y.length());
            if (max > 0 && (min * 1.0 / max) >= 0.88) return true;
        }
        return bigramJaccard(x, y) >= 0.86;
    }

    private String normalizeForSimilarity(String s) {
        String src = s == null ? "" : s;
        StringBuilder sb = new StringBuilder(src.length());
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            boolean isCjk = c >= 0x4E00 && c <= 0x9FFF;
            if (isCjk || Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private double bigramJaccard(String a, String b) {
        java.util.HashSet<String> sa = new java.util.HashSet<>();
        java.util.HashSet<String> sb = new java.util.HashSet<>();
        for (int i = 0; i + 1 < a.length(); i++) sa.add(a.substring(i, i + 2));
        for (int i = 0; i + 1 < b.length(); i++) sb.add(b.substring(i, i + 2));
        if (sa.isEmpty() || sb.isEmpty()) return 0.0;

        int inter = 0;
        for (String g : sa) {
            if (sb.contains(g)) inter++;
        }
        int union = sa.size() + sb.size() - inter;
        if (union <= 0) return 0.0;
        return inter * 1.0 / union;
    }

    private ParsedLoginCare parseFromPayloadAi(Map<?, ?> payload) {
        Object aiObj = payload.get("ai");
        if (aiObj instanceof Map<?, ?> ai) {
            Object up = ai.get("userPrompt");
            if (up != null) return parseLoginCare(String.valueOf(up));
        }
        return new ParsedLoginCare();
    }

    private String getTrigger(Map<?, ?> payload) {
        Object dataObj = payload.get("data");
        if (dataObj instanceof Map<?, ?> data) {
            Object t = data.get("trigger");
            if (t != null) return String.valueOf(t);
        }
        return "";
    }

    private ParsedLoginCare parseLoginCare(String userPrompt) {
        ParsedLoginCare out = new ParsedLoginCare();
        String s = userPrompt == null ? "" : userPrompt;
        out.streak = parseLong(s, "\"streak\"\\s*:\\s*(\\d+)", "连续打卡\\s*=\\s*(\\d+)");
        Long total = parseLongObj(s, "\"todayTotal\"\\s*:\\s*(\\d+)", "今日总数\\s*=\\s*(\\d+)");
        out.total = total != null ? total.intValue() : null;
        Long pending = parseLongObj(s, "\"todayPending\"\\s*:\\s*(\\d+)", "未完成\\s*=\\s*(\\d+)");
        out.pending = pending != null ? pending.intValue() : null;
        out.nextTask = parseString(s, "\"nextTask\"\\s*:\\s*\"([^\"]*)\"", "下一项任务\\s*=\\s*([^，。\\n]+)");
        out.mood = parseString(s, "\"latestMood\"\\s*:\\s*\"([^\"]*)\"", "最近心情\\s*=\\s*([^，。\\n]+)");
        return out;
    }

    private long parseLong(String s, String re1, String re2) {
        Long a = parseLongObj(s, re1, re2);
        return a != null ? a : 0L;
    }

    private Long parseLongObj(String s, String re1, String re2) {
        Long v = matchLong(s, re1);
        if (v != null) return v;
        return matchLong(s, re2);
    }

    private Long matchLong(String s, String re) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(re).matcher(s);
            if (!m.find()) return null;
            return Long.valueOf(m.group(1));
        } catch (Exception e) {
            return null;
        }
    }

    private String parseString(String s, String re1, String re2) {
        String v = matchString(s, re1);
        if (v != null) return v;
        return matchString(s, re2);
    }

    private String matchString(String s, String re) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(re).matcher(s);
            if (!m.find()) return null;
            return m.group(1);
        } catch (Exception e) {
            return null;
        }
    }

    private static class ParsedLoginCare {
        long streak;
        Integer total;
        Integer pending;
        String nextTask;
        String mood;
    }

    private Integer extractDoneCount(String t) {
        Integer v = matchInt(t, "(?:已完成|完成了)\\s*(\\d+)");
        if (v != null) return v;
        return matchInt(t, "(?:已完成|完成)\\s*(\\d+)\\s*个");
    }

    private Integer matchInt(String s, String re) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(re).matcher(s == null ? "" : s);
            if (!m.find()) return null;
            return Integer.valueOf(m.group(1));
        } catch (Exception e) {
            return null;
        }
    }

    private String sanitize(String s) {
        String x = s == null ? "" : s;
        x = x.replace("```", " ").replace("**", " ").replace("#", " ").replace("*", " ");
        x = x.replace("\r", " ").replace("\n", " ").trim();
        if (x.length() > 120) x = x.substring(0, 120).trim();
        return x;
    }
}
